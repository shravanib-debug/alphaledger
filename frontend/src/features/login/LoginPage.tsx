import { useState, type FormEvent } from 'react'
import { Navigate, useNavigate, useSearchParams } from 'react-router'

import { toApiError } from '../../api/http'
import { useAuth } from '../../auth/AuthProvider'
import { Button } from '../../components/ui/Button'
import { Field, Input } from '../../components/ui/Field'

const DEMO_USERS = [
  { email: 'priya.pm@alphaledger.test', label: 'Priya', role: 'Portfolio Manager' },
  { email: 'rohan.ops@alphaledger.test', label: 'Rohan', role: 'Operations Analyst' },
  { email: 'anita.admin@alphaledger.test', label: 'Anita', role: 'Administrator' },
  { email: 'vikram.auditor@alphaledger.test', label: 'Vikram', role: 'Auditor' },
]

export function LoginPage() {
  const { login, status } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const next = safeNext(params.get('next'))
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  if (status === 'authenticated') {
    return <Navigate to={next} replace />
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await login(email, password)
      navigate(next, { replace: true })
    } catch (err) {
      const apiError = toApiError(err)
      setError(apiError.status === 401 ? 'Email or password is incorrect.' : apiError.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="grid min-h-[100dvh] lg:grid-cols-2">
      <section className="flex flex-col justify-between border-b border-border bg-[#f3eee7] px-8 py-10 lg:border-b-0 lg:border-r lg:px-16 lg:py-14">
        <span className="font-serif text-[19px] tracking-[0.22em]">ALPHALEDGER</span>
        <div className="my-14 max-w-[480px]">
          <h1 className="type-headline">Portfolio management and reconciliation for Indian markets.</h1>
          <p className="type-description mt-4">
            Trades, positions, NAV and custodian reconciliation in one place, valued at real NSE and AMFI prices.
          </p>
        </div>
        <span className="h-px w-16 bg-taupe" />
      </section>

      <section className="flex items-center px-8 py-12 lg:px-16">
        <form onSubmit={submit} className="w-full max-w-[380px]" noValidate>
          <h2 className="type-section">Sign in</h2>
          <div className="mt-6 space-y-4">
            <Field label="Email">
              {(a) => <Input {...a} type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />}
            </Field>
            <Field label="Password" error={error}>
              {(a) => (
                <Input {...a} type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
              )}
            </Field>
          </div>
          <Button type="submit" variant="primary" className="mt-6 w-full" disabled={busy || !email || !password}>
            {busy ? 'Signing in' : 'Sign in'}
          </Button>

          {import.meta.env.DEV && (
            <div className="mt-10 border-t border-border pt-5">
              <p className="type-label">Demo users (development)</p>
              <ul className="mt-3 grid grid-cols-2 gap-2">
                {DEMO_USERS.map((u) => (
                  <li key={u.email}>
                    <button
                      type="button"
                      onClick={() => {
                        setEmail(u.email)
                        setPassword('Demo@AlphaLedger2026')
                      }}
                      className="w-full rounded-[var(--radius-control)] border border-border px-3 py-2 text-left hover:border-taupe hover:bg-ivory-raised"
                    >
                      <span className="block text-[13px]">{u.label}</span>
                      <span className="type-meta">{u.role}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </form>
      </section>
    </div>
  )
}

/** Only same-app paths are followed after sign-in, never another site. */
function safeNext(next: string | null): string {
  return next && next.startsWith('/') && !next.startsWith('//') ? next : '/'
}
