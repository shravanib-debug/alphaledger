/**
 * The API client for every service (all through the gateway, same origin). Adds the Bearer token, renews the
 * session once on a 401 and retries the request, and turns failures into ApiError (docs/ui-plan.md §5.4).
 */
import axios, { type AxiosError, type AxiosRequestConfig } from 'axios'

import { accessToken, refreshSession } from '../auth/session'
import type { ErrorBody } from './types'

export const http = axios.create({ baseURL: '/api/v1', withCredentials: true })

let onSessionExpired: () => void = () => undefined

/** Called once by the app shell: where to go when the session cannot be renewed. */
export function setSessionExpiredHandler(handler: () => void): void {
  onSessionExpired = handler
}

http.interceptors.request.use((config) => {
  const token = accessToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

http.interceptors.response.use(undefined, async (error: AxiosError) => {
  const original = error.config as (AxiosRequestConfig & { _retried?: boolean }) | undefined
  if (error.response?.status === 401 && original && !original._retried) {
    original._retried = true
    try {
      await refreshSession()
    } catch {
      onSessionExpired()
      throw toApiError(error)
    }
    return http.request(original)
  }
  throw toApiError(error)
})

/** A failed API call, in the standard error format, with per-field messages split out for forms. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly correlationId?: string
  readonly fieldErrors: Record<string, string>

  constructor(status: number, code: string, message: string, correlationId?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.correlationId = correlationId
    this.fieldErrors = status === 400 ? parseFieldErrors(message) : {}
  }
}

/** "quantity: must be greater than 0; price: must not be null" -> { quantity: ..., price: ... }. */
export function parseFieldErrors(message: string): Record<string, string> {
  const fields: Record<string, string> = {}
  for (const part of message.split('; ')) {
    const match = /^([A-Za-z][\w.]*): (.+)$/.exec(part.trim())
    if (match?.[1] && match[2]) {
      fields[match[1]] = match[2]
    }
  }
  return fields
}

export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }
  if (axios.isAxiosError(error)) {
    const res = error.response
    if (!res) {
      return new ApiError(0, 'NETWORK', 'Cannot reach AlphaLedger. Check your connection and try again.')
    }
    const body = res.data as Partial<ErrorBody> | undefined
    const correlationId = body?.correlationId ?? (res.headers['x-correlation-id'] as string | undefined)
    const message = body?.message ?? defaultMessage(res.status)
    return new ApiError(res.status, body?.error ?? `HTTP_${res.status}`, message, correlationId)
  }
  return new ApiError(0, 'UNKNOWN', error instanceof Error ? error.message : 'Something went wrong')
}

function defaultMessage(status: number): string {
  switch (status) {
    case 403:
      return 'Your role does not allow this action'
    case 404:
      return 'Not found'
    case 429:
      return 'Too many requests; retrying shortly'
    case 503:
      return 'The service is temporarily unavailable; try again shortly'
    default:
      return `Request failed (${status})`
  }
}
