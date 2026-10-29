import { ArrowClockwise } from '@phosphor-icons/react'
import type { ReactNode } from 'react'

import { cn } from '../../lib/cn'
import { Button } from './Button'

/** One sentence and at most one action. */
export function EmptyState({ message, action }: { message: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-start gap-3 py-10">
      <p className="type-description text-[16px]">{message}</p>
      {action}
    </div>
  )
}

/**
 * What failed, in the service's own words, with a retry. The correlation ID lets a problem be traced in the logs.
 */
export function ErrorState({ message, correlationId, onRetry }: {
  message: string
  correlationId?: string
  onRetry?: () => void
}) {
  return (
    <div role="alert" className="flex flex-col items-start gap-3 border-l-2 border-loss py-4 pl-4">
      <p className="text-[14px] text-espresso">{message}</p>
      {correlationId && <p className="type-meta tabular">Reference {correlationId}</p>}
      {onRetry && (
        <Button size="sm" onClick={onRetry}>
          <ArrowClockwise size={13} /> Try again
        </Button>
      )}
    </div>
  )
}

/** Loading placeholder: still hairline bars in the shape of the content, no shimmer. */
export function Skeleton({ className }: { className?: string }) {
  return <span aria-hidden className={cn('block h-3 rounded-[var(--radius-control)] bg-taupe/40', className)} />
}

export function SkeletonRows({ rows = 5, columns = 6 }: { rows?: number; columns?: number }) {
  return (
    <div role="status" aria-label="Loading" className="divide-y divide-border">
      {Array.from({ length: rows }, (_, r) => (
        <div key={r} className="grid gap-6 py-3" style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}>
          {Array.from({ length: columns }, (_, c) => (
            <Skeleton key={c} className={c === 0 ? 'w-3/4' : 'ml-auto w-1/2'} />
          ))}
        </div>
      ))}
    </div>
  )
}
