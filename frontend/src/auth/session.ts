/**
 * Browser session (docs/auth-session.md §6). The access token lives only in this module's memory; the refresh token
 * is an HttpOnly cookie the browser sends to /api/v1/auth and script never sees.
 *
 * Refreshes are coordinated across tabs with the Web Locks API, so two tabs never present the same cookie at once
 * (the server would treat that as token reuse and end every session). Within a tab, concurrent callers share one
 * in-flight refresh. Each attempt carries its own Idempotency-Key; a network-error retry of the same attempt reuses
 * it, which is the only case the server replays.
 */
import axios, { type AxiosError } from 'axios'

import type { TokenResponse, User } from '../api/types'

const WEB = { 'X-Client': 'web' }
const LOCK = 'alphaledger-refresh'
const REFRESH_LEAD_MS = 60_000

/** Auth calls go through a bare client: no Bearer header, no refresh-on-401 loop. */
const authClient = axios.create({ baseURL: '/api/v1/auth', withCredentials: true, headers: WEB })

interface State {
  accessToken: string | null
  user: User | null
}

let state: State = { accessToken: null, user: null }
let inFlight: Promise<User> | null = null
let refreshTimer: ReturnType<typeof setTimeout> | undefined
const listeners = new Set<(user: User | null) => void>()

export function accessToken(): string | null {
  return state.accessToken
}

export function currentUser(): User | null {
  return state.user
}

export function onUserChange(listener: (user: User | null) => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function apply(tokens: TokenResponse): User {
  state = { accessToken: tokens.accessToken, user: tokens.user }
  clearTimeout(refreshTimer)
  const delay = Math.max(5_000, tokens.expiresIn * 1000 - REFRESH_LEAD_MS)
  refreshTimer = setTimeout(() => {
    refreshSession().catch(() => undefined) // a failure surfaces at the next API call
  }, delay)
  listeners.forEach((l) => l(tokens.user))
  return tokens.user
}

export function clearSession(): void {
  clearTimeout(refreshTimer)
  state = { accessToken: null, user: null }
  listeners.forEach((l) => l(null))
}

export async function login(email: string, password: string): Promise<User> {
  const { data } = await authClient.post<TokenResponse>('/login', { email, password })
  return apply(data)
}

/** Restores or renews the session from the cookie; rejects (and clears the session) if it cannot. */
export function refreshSession(): Promise<User> {
  if (!inFlight) {
    inFlight = withLock(doRefresh).finally(() => {
      inFlight = null
    })
  }
  return inFlight
}

async function doRefresh(): Promise<User> {
  const key = newIdempotencyKey()
  for (let attempt = 0; ; attempt++) {
    try {
      const { data } = await authClient.post<TokenResponse>('/refresh', {}, { headers: { 'Idempotency-Key': key } })
      return apply(data)
    } catch (e) {
      const networkError = axios.isAxiosError(e) && !(e as AxiosError).response
      if (networkError && attempt === 0) {
        continue // the response may have been lost: retry the same attempt with the same key
      }
      clearSession()
      throw e
    }
  }
}

export async function logout(): Promise<void> {
  try {
    await withLock(() => authClient.post('/logout', {}))
  } finally {
    clearSession()
  }
}

function withLock<T>(fn: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined
  return locks ? (locks.request(LOCK, fn) as Promise<T>) : fn()
}

function newIdempotencyKey(): string {
  return `web-${crypto.randomUUID().replaceAll('-', '')}`
}
