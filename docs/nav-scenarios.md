# NAV: Hand-Calculated Scenarios (Phase 4B)

> The Phase 4B check "verify three scenarios against a hand-built spreadsheet". Each table below is the spreadsheet: one
> row per event, then the valuation. The same numbers are asserted in
> `services/valuation-service/src/test/java/com/alphaledger/valuation/valuation/NavCalculatorTest.java`
> (`spreadsheetScenario1` to `3`).

**Formula:** `NAV = market value + cash − liabilities` (prd.md FR-VAL-10).
**Rules:** a settled BUY takes `quantity × price` out of cash and a settled SELL pays it in. The management fee
accrues every calendar day as `annual rate × market value / 365`, rounded to paise. Amounts are in INR.

## Scenario 1: deposit, then buy

| # | Event | Cash Δ | Cash | Liabilities |
|---|---|---:|---:|---:|
| 1 | Deposit | +200,000.00 | 200,000.00 | 0.00 |
| 2 | BUY 150 @ 1,033.333333 settles | −155,000.00 | 45,000.00 | 0.00 |

| Market value (150 × 1,050) | Cash | Liabilities | **NAV** |
|---:|---:|---:|---:|
| 157,500.00 | 45,000.00 | 0.00 | **202,500.00** |

## Scenario 2: buy, partial sell, fees and an expense

| # | Event | Cash Δ | Cash | Liabilities |
|---|---|---:|---:|---:|
| 1 | Deposit | +100,000.00 | 100,000.00 | 0.00 |
| 2 | BUY 200 @ 500 settles | −100,000.00 | 0.00 | 0.00 |
| 3 | SELL 80 @ 560 settles | +44,800.00 | 44,800.00 | 0.00 |
| 4 | Fee, 3 days × (0.01 × 64,800.00 / 365 = 1.775… → 1.78) | | 44,800.00 | 5.34 |
| 5 | Expense payable recorded | | 44,800.00 | 505.34 |

| Market value (120 × 540) | Cash | Liabilities | **NAV** |
|---:|---:|---:|---:|
| 64,800.00 | 44,800.00 | 505.34 | **109,094.66** |

## Scenario 3: borrowing, and liabilities exceed assets

| # | Event | Cash Δ | Cash | Liabilities |
|---|---|---:|---:|---:|
| 1 | Borrow (cash in, liability recorded) | +300,000.00 | 300,000.00 | 300,000.00 |
| 2 | BUY 1,000 @ 300 settles | −300,000.00 | 0.00 | 300,000.00 |
| 3 | Price falls to 250 | | 0.00 | 300,000.00 |

| Market value (1,000 × 250) | Cash | Liabilities | **NAV** |
|---:|---:|---:|---:|
| 250,000.00 | 0.00 | 300,000.00 | **−50,000.00** |

A negative NAV is reported as it is, never floored at zero.

## End-to-end check

`NavFlowIT` runs a fourth case through the real service (Kafka, Postgres, REST): deposit 300,000.00; BUY 500 Embassy
REIT @ 430 settles (−215,000.00); seed close 434.91 gives market value 217,455.00; an expense of 2,500.00; one day's
fee 0.01 × 217,455.00 / 365 = 5.96. NAV = 217,455.00 + 85,000.00 − 2,505.96 = **299,949.04**. Paying the expense
moves 2,500.00 from liabilities to cash and leaves NAV unchanged.
