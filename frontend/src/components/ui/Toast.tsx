import { CheckCircle, Copy, WarningCircle, X } from '@phosphor-icons/react'
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'

import { cn } from '../../lib/cn'

interface Toast {
  id: number
  tone: 'success' | 'error' | 'info'
  message: string
  correlationId?: string
}

interface ToastApi {
  success: (message: string) => void
  error: (message: string, correlationId?: string) => void
  info: (message: string) => void
}

const ToastContext = createContext<ToastApi | null>(null)
let nextId = 1

/**
 * Short confirmations and errors, announced politely to screen readers. Errors stay until dismissed and show the
 * correlation ID (with a copy button) so a problem can be traced in the logs.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])

  const dismiss = useCallback((id: number) => setToasts((t) => t.filter((x) => x.id !== id)), [])
  const push = useCallback(
    (toast: Omit<Toast, 'id'>) => {
      const id = nextId++
      setToasts((t) => [...t.slice(-3), { ...toast, id }])
      if (toast.tone !== 'error') {
        setTimeout(() => dismiss(id), 5000)
      }
    },
    [dismiss],
  )

  const api = useMemo<ToastApi>(
    () => ({
      success: (message) => push({ tone: 'success', message }),
      error: (message, correlationId) => push({ tone: 'error', message, correlationId }),
      info: (message) => push({ tone: 'info', message }),
    }),
    [push],
  )

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div aria-live="polite" className="fixed bottom-4 right-4 z-[60] flex w-[min(380px,calc(100vw-32px))] flex-col gap-2">
        {toasts.map((t) => (
          <div
            key={t.id}
            role={t.tone === 'error' ? 'alert' : 'status'}
            className={cn(
              'flex items-start gap-2.5 rounded-[var(--radius-panel)] border bg-ivory px-3.5 py-3 text-[13px] shadow-[var(--shadow-float)]',
              t.tone === 'error' ? 'border-loss/40' : 'border-border',
            )}
          >
            {t.tone === 'error' ? (
              <WarningCircle size={16} className="mt-0.5 shrink-0 text-loss" />
            ) : (
              <CheckCircle size={16} className="mt-0.5 shrink-0 text-gain" />
            )}
            <div className="min-w-0 flex-1">
              <p>{t.message}</p>
              {t.correlationId && (
                <button
                  type="button"
                  onClick={() => void navigator.clipboard?.writeText(t.correlationId!)}
                  className="type-meta tabular mt-1 inline-flex items-center gap-1 hover:text-espresso"
                >
                  Reference {t.correlationId} <Copy size={11} />
                </button>
              )}
            </div>
            <button type="button" aria-label="Dismiss" onClick={() => dismiss(t.id)} className="text-grey hover:text-espresso">
              <X size={14} />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext)
  if (!ctx) {
    throw new Error('useToast outside ToastProvider')
  }
  return ctx
}
