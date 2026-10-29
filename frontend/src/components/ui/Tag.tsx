import type { ReactNode } from 'react'

import { cn } from '../../lib/cn'

export type TagTone = 'neutral' | 'outline' | 'positive' | 'negative' | 'warn' | 'accent'

const TONES: Record<TagTone, string> = {
  neutral: 'bg-taupe/45 text-espresso border-transparent',
  outline: 'bg-transparent text-espresso border-border',
  positive: 'bg-transparent text-gain border-gain/35',
  negative: 'bg-transparent text-loss border-loss/35',
  warn: 'bg-warn-tint text-warn border-transparent',
  accent: 'bg-burgundy-tint text-burgundy border-transparent',
}

/** A quiet status label: small uppercase text, square corners, always carrying its meaning in words. */
export function Tag({ tone = 'neutral', children, title }: { tone?: TagTone; children: ReactNode; title?: string }) {
  return (
    <span
      title={title}
      className={cn(
        'inline-flex h-5 items-center rounded-[var(--radius-control)] border px-1.5',
        'text-[10.5px] font-medium uppercase leading-none tracking-[0.06em]',
        TONES[tone],
      )}
    >
      {children}
    </span>
  )
}

const STATUS_TONES: Record<string, TagTone> = {
  NEW: 'outline',
  VALIDATED: 'outline',
  SETTLED: 'neutral',
  REJECTED: 'negative',
  CANCELLED: 'neutral',
  OPEN: 'accent',
  IN_REVIEW: 'outline',
  ESCALATED: 'warn',
  RESOLVED: 'neutral',
  PASSED: 'positive',
  FAILED: 'negative',
  COMPLETED: 'positive',
  RUNNING: 'outline',
  PAID: 'neutral',
}

/** A backend status value (trade, break, run, check) in its standard tone; underscores read as spaces. */
export function StatusTag({ status }: { status: string }) {
  return <Tag tone={STATUS_TONES[status] ?? 'outline'}>{status.replaceAll('_', ' ')}</Tag>
}
