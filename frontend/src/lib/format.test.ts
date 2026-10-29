import {
  amount,
  calendarDate,
  compactRupees,
  direction,
  marketDateLong,
  marketToday,
  percent,
  price,
  quantity,
  signedAmount,
  timestamp,
} from './format'

describe('amounts use Indian digit grouping', () => {
  it('formats table amounts with two decimals', () => {
    expect(amount(2456500)).toBe('24,56,500.00')
    expect(amount(215000)).toBe('2,15,000.00')
    expect(amount(85000)).toBe('85,000.00')
    expect(amount(5.957)).toBe('5.96')
  })

  it('keeps quantity decimals only when present', () => {
    expect(quantity(1000)).toBe('1,000')
    expect(quantity(12.5)).toBe('12.5')
    expect(quantity(150000)).toBe('1,50,000')
  })

  it('shows prices with two to four decimals', () => {
    expect(price(434.91)).toBe('434.91')
    expect(price(1050)).toBe('1,050.00')
    expect(price(97.6025)).toBe('97.6025')
  })

  it('formats percentages', () => {
    expect(percent(50.567)).toBe('50.57%')
    expect(percent(27)).toBe('27.00%')
  })
})

describe('headline amounts in lakh and crore', () => {
  it('picks the unit by size', () => {
    expect(compactRupees(124_860_000)).toBe('₹ 12.49 Cr')
    expect(compactRupees(10_000_000)).toBe('₹ 1.00 Cr')
    expect(compactRupees(386_000)).toBe('₹ 3.86 L')
    expect(compactRupees(85_000)).toBe('₹ 85,000.00')
  })

  it('keeps a negative NAV negative', () => {
    expect(compactRupees(-12_540)).toBe('-₹ 12,540.00')
    expect(compactRupees(-5_000_000)).toBe('-₹ 50.00 L')
  })
})

describe('P&L sign and direction', () => {
  it('signs values explicitly', () => {
    expect(signedAmount(5000)).toBe('+5,000.00')
    expect(signedAmount(-400)).toBe('-400.00')
    expect(signedAmount(0)).toBe('0.00')
  })

  it('classifies direction', () => {
    expect(direction(1)).toBe('gain')
    expect(direction(-0.01)).toBe('loss')
    expect(direction(0)).toBe('flat')
  })
})

describe('dates and times', () => {
  it('shows API calendar dates without shifting the day', () => {
    expect(calendarDate('2026-10-15')).toBe('15 Oct 2026')
    expect(calendarDate('2026-01-01')).toBe('1 Jan 2026')
  })

  it('shows timestamps in IST', () => {
    expect(timestamp('2026-10-14T20:30:00Z')).toBe('15 Oct 2026, 02:00 IST')
  })

  it('uses the market zone for today', () => {
    // 20:00 UTC on 15 Oct is already 16 Oct in India.
    const lateUtc = new Date('2026-10-15T20:00:00Z')
    expect(marketToday(lateUtc)).toBe('2026-10-16')
    expect(marketDateLong(lateUtc)).toBe('16 Oct 2026 · Fri')
  })
})
