import { calendarDate } from '../../lib/format'
import { Tooltip } from './Tooltip'

const LABELS: Record<string, string> = {
  NSE_BHAVCOPY: 'NSE',
  AMFI_NAV: 'AMFI',
  MFAPI: 'MFAPI',
  SYNTHETIC: 'SYNTH',
  CSV_UPLOAD: 'CSV',
  YFINANCE: 'YF',
}

const DESCRIPTIONS: Record<string, string> = {
  NSE_BHAVCOPY: 'NSE bhavcopy closing price',
  AMFI_NAV: 'AMFI published NAV',
  MFAPI: 'mfapi.in NAV (fallback)',
  SYNTHETIC: 'Synthetic model mark',
  CSV_UPLOAD: 'Uploaded price file',
  YFINANCE: 'Yahoo Finance close',
}

/**
 * Where a price came from and for which date. Stale prices turn amber and say so in words; synthetic marks show
 * their fair-value level (L3).
 */
export function SourceBadge({ source, dataDate, stale, fairValueLevel }: {
  source: string
  dataDate: string
  stale: boolean
  fairValueLevel?: string | null
}) {
  const label = `${LABELS[source] ?? source}${fairValueLevel ? ` ${fairValueLevel}` : ''}`
  return (
    <Tooltip content={`${DESCRIPTIONS[source] ?? source}, ${calendarDate(dataDate)}${stale ? '. Older than the latest business day.' : ''}`}>
      <span
        className={
          'inline-flex h-5 items-center gap-1.5 whitespace-nowrap rounded-[var(--radius-control)] border px-1.5 text-[10.5px] leading-none tabular ' +
          (stale ? 'border-transparent bg-warn-tint text-warn' : 'border-border text-grey')
        }
      >
        <span className="font-medium tracking-[0.04em]">{label}</span>
        <span>{calendarDate(dataDate)}</span>
        {stale && <span className="font-medium uppercase tracking-[0.06em]">stale</span>}
      </span>
    </Tooltip>
  )
}
