/**
 * Number and date formatting (docs/ui-plan.md §3.4). Amounts arrive from the API as JSON numbers (Java BigDecimal);
 * they are formatted here, never recomputed. Missing values are null and are rendered by the Missing component (a
 * grey "-" with the reason in a tooltip), never as zero.
 */
import { TZDate } from '@date-fns/tz'
import { format as formatDate } from 'date-fns'

export const MARKET_ZONE = 'Asia/Kolkata'
const LAKH = 100_000
const CRORE = 10_000_000

const grouping = (min: number, max: number) =>
  new Intl.NumberFormat('en-IN', { minimumFractionDigits: min, maximumFractionDigits: max })

const AMOUNT = grouping(2, 2)
const QUANTITY = grouping(0, 4)
const PRICE = grouping(2, 4)
const PERCENT = grouping(2, 2)

/** Table amount: Indian grouping, 2 decimals, no currency symbol (it lives in the column header). */
export function amount(value: number): string {
  return AMOUNT.format(value)
}

/** Quantity: Indian grouping, decimals only when present (up to 4). */
export function quantity(value: number): string {
  return QUANTITY.format(value)
}

/** Price: 2 to 4 decimals, so 434.91 stays 434.91 and 97.6025 keeps its precision. */
export function price(value: number): string {
  return PRICE.format(value)
}

export function percent(value: number): string {
  return `${PERCENT.format(value)}%`
}

/**
 * Headline amount with rupee sign, in lakh (L) or crore (Cr) by size; below one lakh it is shown in full.
 * Two decimals: "₹ 12.48 Cr", "₹ 3.86 L", "₹ 85,000.00". The sign stays in front of the symbol.
 */
export function compactRupees(value: number): string {
  const sign = value < 0 ? '-' : ''
  const abs = Math.abs(value)
  if (abs >= CRORE) {
    return `${sign}₹ ${AMOUNT.format(abs / CRORE)} Cr`
  }
  if (abs >= LAKH) {
    return `${sign}₹ ${AMOUNT.format(abs / LAKH)} L`
  }
  return `${sign}₹ ${AMOUNT.format(abs)}`
}

export type Direction = 'gain' | 'loss' | 'flat'

export function direction(value: number): Direction {
  return value > 0 ? 'gain' : value < 0 ? 'loss' : 'flat'
}

/** Signed P&L text: "+5,000.00", "-400.00", "0.00". Colour and arrow are added by the Pnl component. */
export function signedAmount(value: number): string {
  if (value > 0) {
    return `+${AMOUNT.format(value)}`
  }
  if (value < 0) {
    return `-${AMOUNT.format(Math.abs(value))}`
  }
  return AMOUNT.format(0)
}

/**
 * A calendar date from the API ("2026-10-15") shown as "15 Oct 2026". Parsed as a plain date, never through the
 * browser's time zone, so it cannot shift by a day.
 */
export function calendarDate(isoDate: string): string {
  const [y, m, d] = isoDate.slice(0, 10).split('-').map(Number)
  if (!y || !m || !d) {
    return isoDate
  }
  return formatDate(new Date(y, m - 1, d), 'd MMM yyyy')
}

/** Today's date in the market zone, with weekday: "15 Oct 2026 · Thu". */
export function marketDateLong(now: Date = new Date()): string {
  return formatDate(new TZDate(now, MARKET_ZONE), "d MMM yyyy '·' EEE")
}

/** A timestamp from the API shown in IST: "15 Oct 2026, 02:00 IST". */
export function timestamp(iso: string): string {
  return `${formatDate(new TZDate(new Date(iso), MARKET_ZONE), 'd MMM yyyy, HH:mm')} IST`
}

/** Today in the market zone as yyyy-MM-dd (for "as of" defaults and API parameters). */
export function marketToday(now: Date = new Date()): string {
  return formatDate(new TZDate(now, MARKET_ZONE), 'yyyy-MM-dd')
}
