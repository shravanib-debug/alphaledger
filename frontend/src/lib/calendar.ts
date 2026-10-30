/**
 * Business days for the T+1 settlement preview: weekends and the NSE trading holidays served by
 * /marketdata/holidays. The server computes the real settle date; this only previews it.
 */
export function isBusinessDay(isoDate: string, holidays: Set<string>): boolean {
  const day = new Date(`${isoDate}T00:00:00Z`).getUTCDay()
  return day !== 0 && day !== 6 && !holidays.has(isoDate)
}

export function addBusinessDays(isoDate: string, days: number, holidays: Set<string>): string {
  let d = new Date(`${isoDate}T00:00:00Z`)
  let left = days
  while (left > 0) {
    d = new Date(d.getTime() + 86_400_000)
    if (isBusinessDay(d.toISOString().slice(0, 10), holidays)) {
      left--
    }
  }
  return d.toISOString().slice(0, 10)
}
