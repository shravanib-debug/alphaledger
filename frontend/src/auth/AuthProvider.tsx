import { useQueryClient } from '@tanstack/react-query'
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'

import type { User } from '../api/types'
import { canRead, type Area } from './access'
import { currentUser, login as doLogin, logout as doLogout, onUserChange, refreshSession } from './session'

type Status = 'loading' | 'authenticated' | 'anonymous'

interface AuthContextValue {
  status: Status
  user: User | null
  login: (email: string, password: string) => Promise<User>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

/** Restores the session from the refresh cookie on load, then tracks sign-in and sign-out. */
export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [user, setUser] = useState<User | null>(currentUser())
  const [status, setStatus] = useState<Status>(currentUser() ? 'authenticated' : 'loading')

  useEffect(() => {
    const unsubscribe = onUserChange((u) => {
      setUser(u)
      setStatus(u ? 'authenticated' : 'anonymous')
    })
    if (!currentUser()) {
      refreshSession().catch(() => setStatus('anonymous'))
    }
    return unsubscribe
  }, [])

  const login = useCallback((email: string, password: string) => doLogin(email, password), [])
  const logout = useCallback(async () => {
    await doLogout()
    queryClient.clear() // nothing from the previous user stays cached
  }, [queryClient])

  const value = useMemo(() => ({ status, user, login, logout }), [status, user, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) {
    throw new Error('useAuth outside AuthProvider')
  }
  return ctx
}

/** Signed-in users only; others go to /login and come back afterwards. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  const location = useLocation()
  if (status === 'loading') {
    return (
      <div role="status" className="flex min-h-[100dvh] items-center justify-center">
        <span className="type-label">Restoring your session</span>
      </div>
    )
  }
  if (status === 'anonymous') {
    const next = encodeURIComponent(location.pathname + location.search)
    return <Navigate to={`/login?next=${next}`} replace />
  }
  return <>{children}</>
}

/** An area the role cannot open sends the user to the overview instead. */
export function RequireArea({ area, children }: { area: Area; children: ReactNode }) {
  const { user } = useAuth()
  return canRead(user?.role, area) ? <>{children}</> : <Navigate to="/" replace />
}
