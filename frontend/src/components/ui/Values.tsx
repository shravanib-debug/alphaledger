import type { ReactNode } from 'react'

import { cn } from '../../lib/cn'
import { amount, calendarDate, compactRupees, direction, percent, price, quantity, signedAmount } from '../../lib/format'
import { Tooltip } from './Tooltip'

/** A value that does not exist: a grey "-" with the reason on hover, never a zero. */
export function Missing({ reason }: { reason: string }) {
  return (
    <Tooltip content={reason}>
      <span className="cursor-help text-grey" aria-label={`Not available: ${reason}`}>
        -
      </span>
    </Tooltip>
  )
}

type Maybe = number | null | undefined

function orMissing(value: Maybe, reason: string, render: (v: number) => ReactNode) {
  return value === null || value === undefined ? <Missing reason={reason} /> : render(value)
}

export function Money({ value, compact = false, reason = 'Not available' }: { value: Maybe; compact?: boolean; reason?: string }) {
  return orMissing(value, reason, (v) => (
    <span className={cn('tabular', v < 0 && 'text-loss')}>{compact ? compactRupees(v) : amount(v)}</span>
  ))
}

export function Price({ value, reason = 'No price on or before this date' }: { value: Maybe; reason?: string }) {
  return orMissing(value, reason, (v) => <span className="tabular">{price(v)}</span>)
}

export function Quantity({ value }: { value: Maybe }) {
  return orMissing(value, 'Not available', (v) => <span className="tabular">{quantity(v)}</span>)
}

export function Pct({ value }: { value: Maybe }) {
  return orMissing(value, 'Not available', (v) => <span className="tabular">{percent(v)}</span>)
}

/**
 * Profit or loss: sign, colour and an arrow, so the meaning never depends on colour alone. Flat values stay
 * espresso with no arrow.
 */
export function Pnl({ value, reason = 'Not available' }: { value: Maybe; reason?: string }) {
  return orMissing(value, reason, (v) => {
    const d = direction(v)
    return (
      <span className={cn('tabular whitespace-nowrap', d === 'gain' && 'text-gain', d === 'loss' && 'text-loss')}>
        {d !== 'flat' && (
          <span aria-hidden className="mr-1 text-[9px] align-[1px]">
            {d === 'gain' ? '▲' : '▼'}
          </span>
        )}
        <span className="sr-only">{d === 'gain' ? 'gain ' : d === 'loss' ? 'loss ' : ''}</span>
        {signedAmount(v)}
      </span>
    )
  })
}

export function DateText({ value }: { value: string | null | undefined }) {
  return value ? <span className="tabular whitespace-nowrap">{calendarDate(value)}</span> : <Missing reason="No date" />
}
