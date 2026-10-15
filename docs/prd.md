# AlphaLedger: Product Requirements Document (PRD)

> **Portfolio Management & Trade Reconciliation Platform**
> Domain: Asset Management / Banking Operations
> Version: 1.2 | Status: Draft | Type: Portfolio project (Java, Spring Boot, microservices)
> v1.2: Indian market context (INR, T+1, ISIN) and hybrid data (real public prices, synthetic transactions).

---

## 1. Overview

### 1.1 One-line summary
AlphaLedger is an event-driven microservices platform where trades flow in, positions and portfolio valuations update automatically, and daily records are reconciled against an external custodian file, with mismatches ("breaks") managed through a web dashboard.

### 1.2 Background
Asset managers (such as alternative investment firms) manage money for investors across asset classes like Private Equity, Credit, and Real Estate. Every day they must:

1. Record trades.
2. Know what they currently hold and what it is worth.
3. Prove that their records match the custodian bank's records.

In practice these steps are handled by separate systems (order/trade management, portfolio accounting, reconciliation tooling). AlphaLedger simulates all three in one cohesive, modern architecture.

### 1.3 Problem statement
- Trade, position, and valuation data are often inconsistent across systems.
- Manual reconciliation (spreadsheets) is slow, error-prone, and unauditable.
- Operations teams need a clear queue of exceptions, with ownership and an audit trail.
- Portfolio managers need near real-time visibility of positions, market value (NAV from v1.5), and P&L.

### 1.4 Purpose of this project
- Serve as a realistic, interview-ready portfolio project aligned with a Technical Intern (Technology) role in asset management.
- Demonstrate: Java/Spring Boot microservices, Kafka event-driven design, SQL, ReactJS, containerization, CI/CD, TDD/JUnit, and data engineering/analytics.

### 1.5 Market context and data approach
- **Market:** Indian markets. Base currency **INR**; instruments identified by **ISIN** (exchange symbol is secondary); settlement **T+1 business day** using the NSE trading-holiday calendar.
- **Hybrid data:** **prices and instrument reference data are real public data** (NSE end-of-day bhavcopy, AMFI mutual fund NAVs). **Trades, positions, the custodian file, and reconciliation breaks are synthetic**: real trade and custodian data is private, and reconciliation needs deliberately injected breaks to prove the matching engine works. See §4.10.

---

## 2. Goals and Non-Goals

### 2.1 Goals
| ID | Goal |
|---|---|
| G1 | Capture and manage the trade lifecycle (NEW → VALIDATED → SETTLED / CANCELLED / REJECTED). |
| G2 | Maintain accurate, event-driven positions per portfolio and instrument. |
| G3 | Compute portfolio market value, P&L, and asset-class exposure (v1), and extend to full NAV (market value + cash − liabilities) in v1.5. |
| G4 | Automatically reconcile internal settled trades against a custodian file and manage breaks end-to-end. |
| G5 | Provide a role-based React dashboard for PMs and Operations Analysts. |
| G6 | Provide a data pipeline producing analytics-ready (warehouse-style) tables. |
| G7 | Ship with automated tests, CI/CD, Docker-based deployment, and good documentation. |

### 2.2 Non-Goals (out of scope)
- Real money movement, real market connectivity (FIX, Bloomberg), or real custodian integrations.
- Intraday/real-time market data and paid broker APIs (Kite Connect, Upstox, etc.). Only free, public end-of-day files are used.
- Complex derivatives pricing, FX hedging, or tax lot accounting.
- Regulatory reporting (e.g., Form PF) and real KYC vendor integrations.
- Multi-tenancy and enterprise SSO.
- Production-grade cloud infrastructure (cloud deployment is optional and a stretch goal).

---

## 3. Users and Personas

| Persona | Role | Needs |
|---|---|---|
| **Priya, Portfolio Manager (PM)** | Makes investment decisions | Enter trades, view positions, market value/NAV, P&L, allocation by asset class |
| **Rohan, Operations Analyst** | Ensures data accuracy | Run/inspect reconciliation, investigate and resolve breaks, keep an audit trail |
| **Anita, Admin** | Platform owner | Manage users and roles, instruments, system configuration |
| **Vikram, Auditor (read-only)** | Compliance | View trades, breaks, and audit logs without edit rights |

### Role permissions
| Capability | Admin | PM | Ops Analyst | Auditor |
|---|---|---|---|---|
| Manage users/roles | ✅ | ❌ | ❌ | ❌ |
| Manage portfolios/instruments | ✅ | ✅ (own portfolios) | ❌ | ❌ |
| Create/cancel trades | ✅ | ✅ | ❌ | ❌ |
| View positions/valuation/P&L | ✅ | ✅ | ✅ | ✅ |
| Upload custodian file / run recon | ✅ | ❌ | ✅ | ❌ |
| Resolve/assign breaks | ✅ | ❌ | ✅ | ❌ |
| View audit log | ✅ | ❌ | ✅ | ✅ |

---

## 4. Scope and Features

Features are prioritized: **P0** = must have (MVP), **P1** = should have, **P2** = nice to have.

### 4.1 Authentication & Authorization (Auth Service)
| ID | Requirement | Priority |
|---|---|---|
| FR-AUTH-1 | Users can register (Admin only) and log in with email/password. | P0 |
| FR-AUTH-2 | System issues short-lived JWT access tokens and refresh tokens. | P0 |
| FR-AUTH-3 | Role-based access control enforced at the gateway and service level. | P0 |
| FR-AUTH-4 | Passwords stored with BCrypt hashing. | P0 |
| FR-AUTH-5 | Account lockout after repeated failed logins. | P2 |

### 4.2 Portfolio & Instrument Management (Portfolio Service)
| ID | Requirement | Priority |
|---|---|---|
| FR-PF-1 | CRUD for portfolios (name, manager, base currency (default INR), status). | P0 |
| FR-PF-2 | Each portfolio is organized into funds tagged by asset class (PRIVATE_EQUITY, CREDIT, REAL_ESTATE). | P0 |
| FR-PF-3 | CRUD for instruments: ISIN (primary identifier, unique), symbol (secondary), exchange, name, asset class, asset-class proxy, instrument type, currency (INR), synthetic flag. | P0 |
| FR-PF-4 | Seed realistic demo portfolios and instruments (real ISINs for listed instruments and mutual funds; see §10). | P0 |
| FR-PF-5 | Soft-delete / archive portfolios. | P1 |

### 4.3 Trade Management (Trade Service)
| ID | Requirement | Priority |
|---|---|---|
| FR-TR-1 | Create a trade: portfolio, instrument (by ISIN), side (BUY/SELL), quantity, price (INR), trade date, settle date (optional; defaults to trade date + 1 business day, see FR-DATA-9). | P0 |
| FR-TR-2 | Validate: portfolio exists and is active, instrument exists, quantity > 0, price > 0, settle date ≥ trade date and is a business day, no SELL beyond current holdings. | P0 |
| FR-TR-3 | Maintain trade status lifecycle: `NEW → VALIDATED → SETTLED`, plus `REJECTED` and `CANCELLED`. | P0 |
| FR-TR-4 | Publish `trade.created` on creation and `trade.settled` on settlement. | P0 |
| FR-TR-5 | Trade blotter API with filtering (date, portfolio, status, instrument) and pagination. | P0 |
| FR-TR-6 | Cancel a trade before settlement. | P1 |
| FR-TR-7 | Bulk trade upload via CSV. | P1 |
| FR-TR-8 | Auto-settle trades when settle date is reached (scheduled job). | P1 |
| FR-TR-9 | Idempotent trade creation using a client-supplied reference. | P1 |

### 4.4 Position Management (Position Service)
| ID | Requirement | Priority |
|---|---|---|
| FR-POS-1 | Consume `trade.created` events and update quantity and average cost per portfolio + instrument. | P0 |
| FR-POS-2 | Use weighted-average cost for BUYs; realize P&L on SELLs. | P0 |
| FR-POS-3 | Publish `position.updated` events. | P0 |
| FR-POS-4 | Expose positions API (current holdings per portfolio). | P0 |
| FR-POS-5 | Event processing must be idempotent (duplicate events do not double-count). | P0 |
| FR-POS-6 | Position history / daily snapshots. | P1 |

### 4.5 Valuation & Analytics (Valuation Service)

> **Scope note:** In v1 the platform reports **Portfolio Market Value** (gross value of investments), *not* full NAV. Real-world NAV is total assets minus total liabilities and also accounts for cash, receivables, accrued fees and expenses, payables, borrowings, and fair-value adjustments. FR-VAL-8 to FR-VAL-10 (v1.5) close that gap.
| ID | Requirement | Priority |
|---|---|---|
| FR-VAL-1 | Ingest instrument prices through the pluggable `PriceSource` (NSE bhavcopy, AMFI NAV, synthetic offline mode; FR-DATA-5 to FR-DATA-7) on a schedule, plus manual CSV upload. | P0 |
| FR-VAL-2 | Calculate market value per position = quantity × latest price. | P0 |
| FR-VAL-3 | Calculate **portfolio market value** (gross value of investments), unrealized P&L, and realized P&L. | P0 |
| FR-VAL-4 | Exposure breakdown by asset class, currency, and instrument. | P0 |
| FR-VAL-5 | Store a daily valuation snapshot (market value, P&L) per portfolio (end-of-day batch). | P0 |
| FR-VAL-6 | Valuation history API for charting. | P1 |
| FR-VAL-7 | Limit monitoring: alert if exposure to an asset class exceeds a configured threshold. | P1 |
| FR-VAL-8 | **(v1.5)** Maintain a cash balance per portfolio: settled BUYs reduce cash, settled SELLs increase cash; manual deposit/withdrawal entries supported. | P1 |
| FR-VAL-9 | **(v1.5)** Track liabilities and accruals (accrued management fees, operating expenses, payables for unsettled purchases, borrowings). | P1 |
| FR-VAL-10 | **(v1.5)** Calculate full **NAV = market value + cash − liabilities**, stored with its components and a `valuation_basis` flag (`MARKET_VALUE_ONLY` or `FULL_NAV`). | P1 |
| FR-VAL-11 | Per-unit NAV (NAV ÷ units outstanding). | P2 |
| FR-VAL-12 | Valuation override and fair-value level tag (L1/L2/L3) for illiquid holdings with no daily market price. | P2 |

### 4.6 Reconciliation (Reconciliation Service), the signature feature
| ID | Requirement | Priority |
|---|---|---|
| FR-REC-1 | Ingest a custodian file (CSV, instruments identified by ISIN) via upload or scheduled drop folder. | P0 |
| FR-REC-2 | Parse and validate the file (schema, required fields, data types); reject bad rows into an error report. | P0 |
| FR-REC-3 | Match custodian records to internal settled trades on `trade_ref + instrument + trade_date`, where instrument is keyed by ISIN (`trade_ref + isin + trade_date`). | P0 |
| FR-REC-4 | Compare quantity and price within a configurable tolerance. | P0 |
| FR-REC-5 | Create breaks of type `MISSING_INTERNAL`, `MISSING_EXTERNAL`, `QTY_MISMATCH`, `PRICE_MISMATCH`. | P0 |
| FR-REC-6 | Each reconciliation run has a summary: total records, matched, breaks by type, match rate. | P0 |
| FR-REC-7 | Break workflow: `OPEN → IN_REVIEW → RESOLVED / ESCALATED`, with assignment to an analyst. | P0 |
| FR-REC-8 | Resolution actions: accept external value, adjust internal record, mark as known difference, escalate. Each requires a comment. | P0 |
| FR-REC-9 | Full audit trail of every break action (who, what, when, before/after). | P0 |
| FR-REC-10 | Publish `recon.break.created` and `recon.break.resolved` events. | P0 |
| FR-REC-11 | Reconciliation runs are idempotent (re-running the same file does not duplicate breaks). | P1 |
| FR-REC-12 | Break ageing (days open) and SLA highlighting. | P1 |
| FR-REC-13 | Configurable matching rules and tolerances per source. | P2 |

### 4.7 Notifications (Notification Service)
| ID | Requirement | Priority |
|---|---|---|
| FR-NOT-1 | Consume `recon.break.created` and `limit.breached` events and create in-app notifications. | P0 |
| FR-NOT-2 | Notification API (list, mark as read). | P0 |
| FR-NOT-3 | Email notification (via a mock/SMTP sink such as MailHog). | P1 |
| FR-NOT-4 | Daily digest of open breaks. | P2 |

### 4.8 Reporting & Data Pipeline
| ID | Requirement | Priority |
|---|---|---|
| FR-DATA-1 | Pipeline loads trades, positions, valuation, and breaks into an analytics schema (star-schema style). | P1 |
| FR-DATA-2 | Pre-built analytical views: AUM by asset class, daily P&L, break rate trend, break ageing. | P1 |
| FR-DATA-3 | Data quality checks (null checks, referential checks, row-count reconciliation between source and target). | P1 |
| FR-DATA-4 | CSV/Excel export of blotter, positions, and break reports. | P2 |
| FR-DATA-5 | Ingest end-of-day prices from the NSE CM bhavcopy for listed instruments (equities, REITs, InvITs, and listed G-secs/NCDs where traded), matched to instruments by ISIN. | P0 |
| FR-DATA-6 | Ingest mutual fund NAVs from AMFI `NAVAll.txt` (Spring Batch job), matched to instruments by ISIN. | P0 |
| FR-DATA-7 | Pluggable `PriceSource` with a configurable fallback order and a synthetic offline mode; the demo, tests, and CI run without network access. The synthetic source prices only `is_synthetic` instruments, plus demo intraday moves in offline mode. In live mode, a real instrument that no real source prices keeps its last real price, flagged stale; a synthetic price is never substituted. | P0 |
| FR-DATA-8 | Price ingestion log (file name, checksum, rows loaded/rejected, status), file-checksum idempotency (re-ingesting a file loads nothing new), and rejected-row reporting. | P1 |
| FR-DATA-9 | NSE trading-holiday calendar (configurable reference table loaded from a committed file) drives business-day settlement (T+1) and expected price dates. | P1 |
| FR-DATA-10 | Backfill of historical prices for a date range (bhavcopy by date; mfapi.in for NAV history). | P2 |

### 4.9 Frontend (React Dashboard)
| ID | Screen | Priority |
|---|---|---|
| FR-UI-1 | Login and role-aware navigation. | P0 |
| FR-UI-2 | **Portfolio Dashboard**: market value (labelled NAV once v1.5 ships), P&L, allocation chart, trend. | P0 |
| FR-UI-3 | **Trade Blotter**: filterable table, create-trade form, status badges. | P0 |
| FR-UI-4 | **Positions** view per portfolio (with ISIN, and a source badge showing `source` + `data_date` next to every price, plus a stale indicator). | P0 |
| FR-UI-5 | **Reconciliation**: upload file, run summary, break list with filters. | P0 |
| FR-UI-6 | **Break Detail**: side-by-side internal vs external values, resolve/assign/escalate, audit history. | P0 |
| FR-UI-7 | Notification bell and list. | P1 |
| FR-UI-8 | Analytics page (break trends, AUM views). | P1 |
| FR-UI-9 | Dark mode and responsive layout. | P2 |

### 4.10 Market Data & Reference Data

AlphaLedger uses **real public end-of-day data** for prices and instrument reference data, and **synthetic data** for everything transactional. Formats and endpoints are detailed in `architecture.md` §5.8; items marked **TO VERIFY** have not been confirmed against official documentation.

| Source | Used for | Role | Notes |
|---|---|---|---|
| **NSE CM bhavcopy** (UDiFF "common bhavcopy", daily CSV in a ZIP) | Equities, REITs, InvITs, listed G-secs and NCDs | **Primary** price source for listed instruments | One row per security per day, keyed by ISIN; closing price `ClsPric`. NSE changed its bhavcopy format in July 2024, so the parser is isolated behind an adapter. |
| **AMFI `NAVAll.txt`** (semicolon-separated flat file) | Mutual fund units | **Primary** price source for mutual funds | Published daily NAV per scheme, keyed by ISIN; ingested by a Spring Batch job. |
| **mfapi.in** (free community JSON API) | Mutual fund NAV history | **Optional** fallback and history backfill only | Never a hard dependency; the platform works if it is down. |
| **yfinance** (`.NS` / `.BO` tickers) | Listed instruments | **Last-resort** manual fallback only | Unofficial; not part of the automated pipeline. |
| **NSE trading-holiday list** | Business-day calendar | Reference data | Committed as a versioned file, loaded into a `holiday_calendar` table. |
| **NSE equity list / exchange data** | Instrument master (ISIN, symbol, series) | Reference data | Used once to build the committed instrument seed file. |

**Explicitly not used:** paid broker APIs (Kite Connect, Upstox, and similar), real-time feeds, and any data that needs a login.

**Instrument universe (Indian proxies for the three asset classes).** Apollo-style private holdings have no public prices, so each asset class maps to listed Indian proxies, plus a few clearly labelled synthetic private instruments:

| Asset class | Real, publicly priced proxies | Price source | Synthetic instruments |
|---|---|---|---|
| `REAL_ESTATE` | Listed REITs and InvITs | NSE bhavcopy | none |
| `CREDIT` | Listed corporate bonds/NCDs and G-secs (where they trade on the CM segment), plus debt mutual funds | NSE bhavcopy (bonds, G-secs); AMFI NAV (debt funds) | none |
| `PRIVATE_EQUITY` | Listed equities and equity mutual funds as proxies | NSE bhavcopy (equities); AMFI NAV (equity funds) | 3 **SYNTHETIC** private companies with **quarterly valuations** (fair-value Level 3 style; see FR-VAL-12 and the v1.5 fair-value note). |

**Two different "NAVs".** Mutual fund units are priced at their published AMFI NAV. Keep the two meanings apart:
- **NAV of a mutual fund (published input):** the per-unit price an AMC publishes for a scheme. AlphaLedger treats it exactly like a closing price: it is stored in `price` with `source = AMFI_NAV` and multiplied by quantity to get market value. In the UI and APIs it is labelled "Price (scheme NAV)".
- **NAV of our portfolio (computed output):** AlphaLedger's own figure. In v1 the platform reports **Portfolio Market Value** only; full **NAV** (market value + cash − liabilities) arrives in v1.5 (FR-VAL-8 to FR-VAL-10). Ingesting scheme NAVs does not make the v1 metric a NAV.

---

## 5. Key Business Rules

1. **Position math**
   - BUY: `new_qty = qty + trade_qty`; `new_avg_cost = (qty × avg_cost + trade_qty × price) / new_qty`.
   - SELL: `new_qty = qty - trade_qty`; average cost unchanged; `realized_pnl += (sell_price - avg_cost) × trade_qty`.
   - A SELL greater than current holdings is rejected (no short selling in v1).
2. **Valuation**
   - **v1: Portfolio Market Value** = Σ (position quantity × latest price), in the base currency (v1: single base currency, INR; FX is a future enhancement). This is *not* NAV: it excludes cash, receivables, accruals, and liabilities, and must be labelled "Market Value" in the UI and APIs.
   - **v1.5: NAV** = market value of investments + cash + other assets − liabilities (accrued fees and expenses, payables for unsettled purchases, borrowings). Per-unit NAV = NAV ÷ units outstanding (P2).
   - Illiquid/alternative holdings are often valued by models or appraisals rather than daily market prices. v1 uses real exchange closing prices (NSE bhavcopy) and published scheme NAVs (AMFI) for listed instruments and mutual funds, and synthetic quarterly marks, held flat between quarter-ends, for the synthetic private instruments.
   - The latest price is the most recent price on or before the valuation date. A price is **stale** when its `data_date` is earlier than the expected price date: the most recent business day on or before the valuation date (for synthetic private instruments, the most recent quarter-end). A stale price is still used, never replaced by a synthetic one, and is shown with its `data_date`. If it is older than a configurable number of business days (default 5), a data-quality warning is also raised (thinly traded bonds may not trade every day).
3. **Unrealized P&L** = Σ (latest price - avg cost) × quantity.
4. **Matching key** for reconciliation: `trade_ref + instrument + trade_date`, with the instrument identified by **ISIN** (`trade_ref + isin + trade_date`). Custodian files identify securities by ISIN, and ISINs are stable across exchanges and symbol changes.
5. **Tolerance**: quantity must match exactly; price may differ within `±₹0.01` (configurable).
6. **Only SETTLED trades** are included in reconciliation.
7. **Break lifecycle** is one-directional: `OPEN → IN_REVIEW → RESOLVED | ESCALATED`. Resolved breaks cannot be reopened (a new break must be created).
8. **Audit**: any state change to a trade or break is logged immutably.
9. **Settlement date**: default `settle_date = trade_date + 1 business day` (T+1, as for Indian equities). A business day is a weekday that is not in the NSE trading-holiday calendar. Example: a trade on Mon 19-Oct-2026 settles on Wed 21-Oct-2026, because Tue 20-Oct-2026 (Dussehra) is an NSE holiday. v1 applies T+1 to every instrument type (simplification).
10. **Currency**: all amounts are in INR (`₹`).

---

## 6. User Flows

### 6.1 Flow A: Trade to Valuation
1. PM logs in and opens the Trade Blotter.
2. PM submits a trade (e.g., BUY 500 units of Embassy Office Parks REIT, ISIN `INE041025011`, @ ₹435.00 for the real-assets portfolio; settle date defaults to T+1).
3. Trade Service validates, saves as `VALIDATED`, publishes `trade.created`.
4. Position Service updates holdings and publishes `position.updated`.
5. Valuation Service recalculates the portfolio's market value and P&L.
6. PM sees updated positions, market value, and P&L on the dashboard.

### 6.2 Flow B: Settlement
1. Settle date (T+1 business day by default) arrives (scheduled job) or an Admin settles manually.
2. Trade Service moves status to `SETTLED` and publishes `trade.settled`.
3. Reconciliation Service stores the settled trade in its reconciliation-ready view.

### 6.3 Flow C: Daily Reconciliation
1. Operations Analyst uploads the custodian CSV (or the scheduler picks it up).
2. Reconciliation Service validates and parses the file.
3. The matching engine compares custodian rows to internal settled trades.
4. Breaks are created and a run summary is produced.
5. Notification Service alerts the ops team.
6. Analyst opens the break list, filters by type/age, and opens a break.
7. Analyst reviews internal vs external values, then resolves, assigns, or escalates, adding a comment.
8. The audit log records every action; the dashboard match rate updates.

### 6.4 Flow D: Limit Alert (P1)
1. Valuation Service detects asset-class exposure above its threshold.
2. Publishes `limit.breached`.
3. Notification Service alerts the PM.

---

## 7. Data Model Summary

(Detailed schemas live in `architecture.md`.)

| Domain | Core entities |
|---|---|
| Auth | `user`, `role`, `refresh_token` |
| Portfolio | `portfolio`, `fund`, `instrument` (keyed by ISIN) |
| Trade | `trade`, `trade_audit`, `holiday_calendar` (local copy for settlement) |
| Position | `position`, `position_snapshot`, `processed_event` |
| Valuation | `price` (with `source`, `data_date`), `instrument_ref`, `holiday_calendar`, `price_ingestion_log`, `price_ingestion_error`, `valuation_snapshot`, `pnl_snapshot`, `limit_rule`, `cash_balance`, `cash_transaction`, `liability` (last three in v1.5) |
| Reconciliation | `recon_run`, `custodian_record`, `recon_break`, `break_action_audit` |
| Notification | `notification` |
| Analytics | `dim_portfolio`, `dim_instrument`, `dim_date`, `fact_trade`, `fact_position_daily`, `fact_valuation_daily`, `fact_recon_break` |

---

## 8. Non-Functional Requirements

| Category | Requirement |
|---|---|
| **Performance** | Trade creation API p95 < 300 ms locally. Reconciliation of 10,000 records < 30 seconds. |
| **Scalability** | Services are stateless and horizontally scalable; Kafka consumers use consumer groups. |
| **Reliability** | At-least-once event delivery with idempotent consumers; dead-letter topics for poison messages; retry with backoff. |
| **Consistency** | Outbox pattern for reliable event publishing from the Trade Service. Eventual consistency between services is acceptable. |
| **Security** | JWT auth, RBAC, BCrypt, input validation, no secrets in the repo (env vars / config server), HTTPS-ready. |
| **Auditability** | Immutable audit logs for trades and breaks. |
| **Observability** | Structured logs with correlation IDs, Actuator health/metrics, Prometheus + Grafana (P1), distributed tracing (P2). |
| **Testability** | Unit tests (JUnit 5 + Mockito), integration tests (Testcontainers), minimum **80% line coverage** enforced by JaCoCo. |
| **Maintainability** | Clean layered code, consistent package structure, Sonar quality gate, OpenAPI docs for every service. |
| **Portability** | Entire stack runs with a single `docker compose up`, fully offline using the committed sample market data. |

---

## 9. Success Metrics

| Metric | Target |
|---|---|
| End-to-end flow works (trade → position → market value) | 100% of demo scenarios |
| v1.5 NAV matches a hand-calculated spreadsheet (market value + cash − liabilities) | 3 of 3 scenarios |
| Reconciliation correctly detects all seeded break types | 4/4 break types, 100% accuracy on test dataset |
| Price ingestion parses the committed sample bhavcopy and NAVAll files | 100% of demo ISINs priced; every rejected row reported with a reason |
| Price ingestion is idempotent | Re-ingesting the same file loads 0 new rows |
| Offline operation | Full demo and CI pipeline pass with external network access disabled |
| Unit + integration test coverage | ≥ 80% |
| CI pipeline | Green on every PR (build, test, coverage, image build) |
| Startup | Full stack up with one command |
| Documentation | README, architecture diagram, API docs, demo walkthrough |

---

## 10. Demo Data Plan

- 3 portfolios with fictional, Indian-context names, base currency INR:
  - *AlphaLedger India Credit Opportunities Fund I* (`CREDIT`)
  - *AlphaLedger Bharat Real Assets Fund* (`REAL_ESTATE`)
  - *AlphaLedger India Growth Equity Fund* (`PRIVATE_EQUITY`)
- 26 **real** instruments identified by real ISINs (table below), plus 3 clearly labelled **synthetic** private instruments.
- ~200 **synthetic** trades across 30 days, generated against **real historical closing prices** (bhavcopy `ClsPric` or AMFI NAV for the trade date) for real instruments, and against the latest quarterly mark for synthetic ones. Settle dates follow T+1 with the NSE holiday calendar.
- A **custodian CSV generator** script (rows keyed by `trade_ref + isin + trade_date`) that produces a file with deliberately injected issues:
  - 3 missing-internal trades
  - 3 missing-external trades
  - 4 quantity mismatches
  - 4 price mismatches (each larger than the price tolerance)
- A committed **offline sample** of real prices for the demo ISINs over the demo window (filtered from the source files; a few hundred rows), so the demo and CI never call external sites. In offline mode only, `SyntheticPriceSource` also provides simulated intraday moves for local demos; these carry a `SYNTHETIC` source badge.

**Demo instrument list.** ISINs below were checked on 15-Oct-2026 against the NSE CM bhavcopy for 14-Oct-2026 (`BhavCopy_NSE_CM_0_0_0_20261014_F_0000.csv`), the NSE equity list (`EQUITY_L.csv`), and AMFI `NAVAll.txt` for 14-Oct-2026. Re-check before seeding, because ISINs can change after corporate actions.

| # | Asset class | Instrument | ISIN | NSE symbol / AMFI code | Series / type | Price source |
|---|---|---|---|---|---|---|
| 1 | REAL_ESTATE | Embassy Office Parks REIT | `INE041025011` | EMBASSY | RR (REIT) | NSE bhavcopy |
| 2 | REAL_ESTATE | Mindspace Business Parks REIT | `INE0CCU25019` | MINDSPACE | RR (REIT) | NSE bhavcopy |
| 3 | REAL_ESTATE | Brookfield India Real Estate Trust | `INE0FDU25010` | BIRET | RR (REIT) | NSE bhavcopy |
| 4 | REAL_ESTATE | Nexus Select Trust | `INE0NDH25011` | NXST | RR (REIT) | NSE bhavcopy |
| 5 | REAL_ESTATE | Knowledge Realty Trust | `INE1JAR25012` | KRT | RR (REIT) | NSE bhavcopy |
| 6 | REAL_ESTATE | IndiGrid Infrastructure Trust | `INE219X23014` | INDIGRID | IV (InvIT) | NSE bhavcopy |
| 7 | REAL_ESTATE | POWERGRID Infrastructure Investment Trust | `INE0GGX23010` | PGINVIT | IV (InvIT) | NSE bhavcopy |
| 8 | REAL_ESTATE | IRB InvIT Fund | `INE183W23014` | IRBINVIT | IV (InvIT) | NSE bhavcopy |
| 9 | CREDIT | GOI 7.06% 2041 (G-sec) | `IN0020260041` | 706GS2041 | GS | NSE bhavcopy |
| 10 | CREDIT | GOI 7.71% 2066 (G-sec) | `IN0020260033` | 771GS2066 | GS | NSE bhavcopy |
| 11 | CREDIT | IRFC 7.34% NCD 19-Feb-2028 | `INE053F07579` | 734IRFC28 | N4 (NCD) | NSE bhavcopy |
| 12 | CREDIT | PFC 7.35% tax-free NCD | `INE134E07570` | 735PFCL35 | N1 (bond) | NSE bhavcopy |
| 13 | CREDIT | NHAI 8.75% tax-free bond | `INE906B07DF8` | 875NHAI29 | N6 (bond) | NSE bhavcopy |
| 14 | CREDIT | HDFC Corporate Bond Fund, Direct, Growth | `INF179K01XD8` | 118987 | Debt MF | AMFI NAV |
| 15 | CREDIT | ICICI Prudential Corporate Bond Fund, Direct, Growth | `INF109K016B1` | 120692 | Debt MF | AMFI NAV |
| 16 | CREDIT | Aditya Birla Sun Life Banking & PSU Debt Fund, Direct, Growth | `INF209K01YN0` | 119550 | Debt MF | AMFI NAV |
| 17 | CREDIT | Nippon India Gilt Fund, Direct, Growth | `INF204K01XP8` | 118672 | Debt MF | AMFI NAV |
| 18 | PRIVATE_EQUITY | Reliance Industries | `INE002A01018` | RELIANCE | EQ | NSE bhavcopy |
| 19 | PRIVATE_EQUITY | HDFC Bank | `INE040A01034` | HDFCBANK | EQ | NSE bhavcopy |
| 20 | PRIVATE_EQUITY | ICICI Bank | `INE090A01021` | ICICIBANK | EQ | NSE bhavcopy |
| 21 | PRIVATE_EQUITY | Infosys | `INE009A01021` | INFY | EQ | NSE bhavcopy |
| 22 | PRIVATE_EQUITY | Tata Consultancy Services | `INE467B01029` | TCS | EQ | NSE bhavcopy |
| 23 | PRIVATE_EQUITY | Larsen & Toubro | `INE018A01030` | LT | EQ | NSE bhavcopy |
| 24 | PRIVATE_EQUITY | Parag Parikh Flexi Cap Fund, Direct, Growth | `INF879O01027` | 122639 | Equity MF | AMFI NAV |
| 25 | PRIVATE_EQUITY | HDFC Flexi Cap Fund, Direct, Growth | `INF179K01UT0` | 118955 | Equity MF | AMFI NAV |
| 26 | PRIVATE_EQUITY | UTI Nifty 50 Index Fund, Direct, Growth | `INF789F01XA0` | 120716 | Index MF | AMFI NAV |

Notes:
- G-secs and NCDs trade thinly on the CM segment, so some days have no bhavcopy row; the stale-price rule (business rule 2) applies. Bhavcopy prices for these are quoted per unit of face value (about ₹100 for the G-secs and about ₹1,000 for the NCDs above), so quantity is in units and market value is still quantity × price.
- Exchange series codes (RR, IV, GS, N1, N4, N6, EQ) are as observed in the 14-Oct-2026 file; their official definitions are **TO VERIFY** against NSE documentation.

**Synthetic private instruments** (`is_synthetic = true`, names prefixed "SYNTHETIC", priced by `SyntheticPriceSource` from committed quarterly marks, fair-value Level 3 style):

| # | Asset class | Instrument | Identifier | Valuation |
|---|---|---|---|---|
| S1 | PRIVATE_EQUITY | SYNTHETIC Deccan Logistics Pvt Ltd, Series B equity | `ZZSYN0000001` | Quarterly mark, held flat between quarter-ends |
| S2 | PRIVATE_EQUITY | SYNTHETIC Konkan Renewables Pvt Ltd, ordinary equity | `ZZSYN0000002` | Quarterly mark, held flat between quarter-ends |
| S3 | PRIVATE_EQUITY | SYNTHETIC Malabar Healthcare Pvt Ltd, growth equity | `ZZSYN0000003` | Quarterly mark, held flat between quarter-ends |

Synthetic identifiers use the `ZZSYN` prefix in the ISIN column. `ZZ` is not assigned to any country, so these can never collide with a real ISIN; they are not valid ISINs and are never sent to any external source.

---

## 11. Assumptions, Dependencies, Risks

### Assumptions
- **Prices are real public data; trades, positions, custodian files and breaks are synthetic.** Instrument reference data (ISIN, symbol, series, scheme code) is real; the 3 synthetic private instruments, their quarterly marks, and the offline-only demo intraday ticks are invented and labelled as such (`SYNTHETIC` source badge). No real client, trade, or custodian data is used.
- Single base currency (INR) in v1. Multi-currency/FX is a future enhancement.
- Market conventions: ISIN is the primary instrument identifier; settlement is T+1 business day on the NSE trading-holiday calendar for all instrument types in v1 (mutual fund and bond settlement cycles that differ are a known simplification; exchange clearing holidays are not modelled separately).
- One custodian source in v1.
- **Valuation scope:** v1 reports **Portfolio Market Value** (gross value of investments), not full NAV. Real-world NAV is total assets minus total liabilities (cash, receivables, accrued fees and expenses, payables, borrowings, fair-value adjustments for illiquid holdings), so the simplified formula does not cover every real-world case. v1.5 adds cash and liabilities to produce a defensible NAV.

### Dependencies
- Docker and Docker Compose for local infrastructure (Kafka, PostgreSQL).
- Java 17+, Node 18+, Maven.
- Public market data sites (NSE archives, AMFI, optionally mfapi.in), needed **only** to refresh data with the download script. The demo, tests, and CI use the committed sample and never need network access.

### Risks and mitigations
| Risk | Mitigation |
|---|---|
| Scope creep across too many services | Strict P0 → P1 → P2 prioritization; finish MVP before extras |
| Kafka/event complexity slows development | Build the happy path first; add DLQ/retries later |
| Distributed-system debugging is hard | Correlation IDs and centralized logging from day one |
| Finance domain unfamiliarity | Glossary in this doc; keep calculations simple and documented |
| Local machine resource limits (many containers) | Compose profiles to start only the services needed |
| Source format changes (NSE changed its bhavcopy format in July 2024; AMFI has added columns) | Each source parsed by an isolated adapter that validates the header; unit tests against saved sample files; a format change fails loudly, affected instruments keep their last real price flagged stale, and mutual funds fall back to mfapi.in |
| Throttling or blocking by source sites | Download once per business day, cache raw files on disk, polite request rate, no scraping of interactive pages; demo never depends on live access |
| Licensing / terms of use | Personal, non-commercial portfolio use only; check each source's terms; never republish raw data dumps in the repo (commit a download script and a small filtered sample instead) |
| Data staleness and gaps (late NAV publication, thinly traded bonds, holidays) | Holiday-aware expected dates, stale flag whenever a price is older than its expected date (plus a warning after a configurable number of business days), source badge with `data_date` in the UI, fallback sources for mutual funds, ingestion log with row counts |
| ISIN changes after corporate actions (splits, mergers) | Instrument master re-checked before seeding; corporate-action handling is out of scope for v1 (manual remap) |

### Data sources: compliance note
- AlphaLedger is a personal, non-commercial portfolio project. Data is used for demonstration only and never redistributed.
- Check each source's current terms of use before downloading (NSE, AMFI, mfapi.in, Yahoo Finance). Their exact terms are **TO VERIFY** and are summarised in the README under "Data sources and licensing".
- The repo contains a **download script** and a **small, filtered sample** (demo ISINs only), never full raw dumps. Raw downloads live in a git-ignored local cache.
- No paid or login-gated APIs are used.

---

## 12. Mapping to the Apollo Technical Intern JD

| JD Requirement | How AlphaLedger demonstrates it |
|---|---|
| Java / Python, Spring Boot, SQL, ReactJS, microservices with cloud support | Entire stack; cloud-ready containers (optional AWS/Azure deploy) |
| Maintain programming standards, framework patterns | Layered architecture, DTO/mapper/service/repository pattern, Checkstyle |
| Code reviews and automatic test coverage | PR workflow, JaCoCo 80% gate, Sonar |
| CI/CD pipeline for automated deployments | GitHub Actions: build → test → scan → Docker image → deploy |
| Spark/Flink, Kafka, Snowflake, Data Analytics (add-on) | Kafka event pipelines; real public market-data ingestion (NSE bhavcopy, AMFI NAV) with Spring Batch, checksums, and an ingestion log; analytics star schema (Snowflake-style); optional Flink/Spark job |
| Containerization and deployments | Docker, Compose, optional Kubernetes |
| Object-oriented, modular, clean code | Domain-driven service boundaries, SOLID |
| TDD and JUnit | Test-first for core logic (position math, matching engine) |
| Teams: Data Engineering, Trade Flow, Reconciliations, Digital Distribution Intelligence | Pipelines (incl. pluggable `PriceSource` adapters), Trade Service (T+1 business-day settlement), Reconciliation Service (ISIN-keyed matching), analytics views |

---

## 13. Future Enhancements (Post-MVP)

- Multi-currency with FX rates and base-currency NAV.
- Corporate-action handling (splits, bonuses, ISIN changes) and per-instrument settlement cycles.
- BSE bhavcopy as a second exchange source.
- Per-unit NAV, performance fee/carry waterfalls, capital calls and distributions, and fair-value levels (L1/L2/L3) for illiquid holdings.
- Multiple custodians with configurable matching rules per source.
- ML-assisted break categorization and suggested resolutions.
- Real-time WebSocket updates on the dashboard.
- Kubernetes deployment with Helm charts.
- Flink streaming job for real-time exposure and P&L.
- Digital Distribution Intelligence module (AUM by channel, region, distributor).

---

## 14. Glossary

| Term | Definition |
|---|---|
| **Asset Class** | Category of investment (Private Equity, Credit, Real Estate). |
| **AUM** | Assets Under Management: total value of assets managed. |
| **Break** | A mismatch found during reconciliation. |
| **Custodian** | Bank/institution that safekeeps assets. |
| **Instrument** | A tradable asset (bond, loan, fund unit, etc.). |
| **NAV** | Net Asset Value: total assets minus total liabilities of a fund (per-unit NAV divides by units outstanding). |
| **Portfolio Market Value** | Σ (quantity × price) of investments; excludes cash and liabilities. Used in v1 in place of NAV. |
| **Fair-value level** | Hierarchy (L1 quoted prices, L2 observable inputs, L3 unobservable/model inputs) used to classify how an asset was valued. |
| **Position** | Current holding in an instrument. |
| **Reconciliation** | Comparing internal records with an external source to find differences. |
| **Settlement** | Final exchange of cash and asset for a trade. |
| **Blotter** | A running list of trades. |
| **Realized / Unrealized P&L** | Profit from closed trades / paper profit on current holdings. |
| **DLQ** | Dead-letter queue: topic that stores messages that failed processing. |
| **ISIN** | International Securities Identification Number: 12-character code identifying a security (Indian ISINs start with `IN`). AlphaLedger's primary instrument key. |
| **Bhavcopy** | NSE's daily end-of-day file of prices and volumes for every traded security. |
| **AMFI** | Association of Mutual Funds in India; publishes daily NAVs for all schemes (`NAVAll.txt`). |
| **Scheme NAV** | Published per-unit NAV of a mutual fund. A price **input** to AlphaLedger, distinct from the portfolio NAV it computes (v1.5). |
| **REIT / InvIT** | Real Estate / Infrastructure Investment Trust: listed trusts holding income-producing real estate / infrastructure. |
| **NCD / G-sec** | Non-convertible debenture (corporate bond) / Government of India security. |
| **T+1** | Settlement one business day after the trade date. |
| **Business day** | A weekday that is not an NSE trading holiday. |
