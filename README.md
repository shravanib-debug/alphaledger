# AlphaLedger

**Portfolio management and trade reconciliation platform for Indian markets.** An event-driven Java / Spring Boot
microservices system: trades flow in, positions and portfolio value update from events, and settled trades are
reconciled against a custodian file, with mismatches ("breaks") worked through a dashboard.

Built as an interview-ready portfolio project for a technology role in asset management. It mirrors the three things
an asset manager must do every day: **record trades, know what it holds and what that is worth, and prove its records
match the custodian's.**

![Java 21](https://img.shields.io/badge/Java-21-blue) ![Spring Boot 4.0](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen)
![Spring Cloud 2025.1](https://img.shields.io/badge/Spring%20Cloud-2025.1-brightgreen) ![Kafka](https://img.shields.io/badge/Kafka-4.2-black)
![PostgreSQL 16](https://img.shields.io/badge/PostgreSQL-16-336791) ![Tests](https://img.shields.io/badge/tests-312%20passing-success)

---

## Contents
- [What it does](#what-it-does)
- [Project status](#project-status)
- [Dashboard](#dashboard)
- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Data: real prices, synthetic transactions](#data-real-prices-synthetic-transactions)
- [How NAV is calculated](#how-nav-is-calculated)
- [Running locally](#running-locally)
- [Trying the API](#trying-the-api)
- [Testing](#testing)
- [Repository layout](#repository-layout)
- [Key design decisions](#key-design-decisions)
- [Documentation](#documentation)
- [License and data](#license-and-data)

---

## What it does

| Area | What the platform does | Status |
|---|---|---|
| **Security** | Email/password login, short-lived JWT access tokens with rotating refresh tokens (row-locked rotation, reuse detection), four roles (Admin, Portfolio Manager, Operations Analyst, Auditor), checks at the gateway and again in every service. Browsers keep the refresh token only in a `Secure; HttpOnly; SameSite=Strict` cookie with Origin checks; API clients use the body | ✅ Built |
| **Portfolios & instruments** | Portfolios split into funds by asset class (Private Equity, Credit, Real Estate); instruments keyed by **ISIN** with check-digit validation; a PM changes only the portfolios they manage | ✅ Built |
| **Trade capture** | BUY/SELL trades in INR with **T+1 settlement on NSE business days**, a status lifecycle (NEW → VALIDATED → SETTLED, REJECTED, CANCELLED), a full audit trail, idempotent trade references, CSV bulk upload, automatic settlement | ✅ Built |
| **Events** | Every change is published to **Kafka** through a transactional outbox: `trade.*`, `position.updated`, `price.updated`, `valuation.calculated`, `limit.breached`; consumers are idempotent | ✅ Built |
| **Market data** | Pluggable price sources; **real NSE and AMFI closing prices** for listed instruments and mutual funds, synthetic Level 3 marks for private holdings; every price shows its source, date and whether it is stale | ✅ Built |
| **Positions & valuation** | Holdings and weighted-average cost rebuilt from trade events (exact cancels, realized P&L); live Market Value, unrealized P&L and exposure by asset class and instrument, every line with its price source; end-of-day snapshots and asset-class limits with breach alerts | ✅ Built |
| **NAV** | NAV = market value + cash − liabilities, with each part shown. Cash comes from an append-only ledger (deposits, withdrawals, settled trades). Liabilities cover fees, expenses, payables and borrowings, plus a daily management-fee accrual | ✅ Built |
| **Reconciliation** (signature feature) | Match a custodian file to settled trades on `trade ref + ISIN + trade date`; detect missing, quantity and price breaks; assign, resolve and escalate with an audit trail | ✅ Done |
| **Notifications & resilience** | In-app and email alerts (MailHog) for reconciliation breaks (Ops) and limit breaches (PMs), with per-user read state and a daily open-breaks digest. Every Kafka consumer retries with backoff, then dead-letters the record and moves on; Admin can list and replay dead letters. Per-route circuit breakers, GET retries and per-user rate limiting at the gateway; retry and circuit breaker on service-to-service calls; correlation IDs across HTTP and Kafka | ✅ Built |
| **Analytics** | Nightly Spring Batch ETL from the service databases into a staging → star schema → mart warehouse (`analytics_db`), with 20+ data-quality checks (row counts, nulls, uniqueness, referential, sanity) that fail the run on critical errors; AUM by asset class, daily P&L, break trend and break ageing views and APIs | ✅ Built |
| **Dashboard** | React UI for all four roles: overview, trades, positions, NAV, reconciliation, notifications, analytics and administration, with role-based menus and routes | ✅ Built |

**Personas** (demo users): Priya (Portfolio Manager), Rohan (Operations Analyst), Anita (Admin), Vikram (Auditor).

---

## Project status

| Phase | Scope | Status |
|---|---|---|
| 0 | Planning, docs, repo, tooling | ✅ |
| 1 | Monorepo, Docker infrastructure, gateway, service registry, config server | ✅ |
| 2 | Authentication, roles, portfolios, instruments | ✅ |
| 3 | Trade capture, lifecycle, outbox, Kafka events | ✅ |
| 4A | Market data: NSE bhavcopy and AMFI NAV parsers, mfapi.in fallback, offline sample, NSE holiday calendar, T+1 | ✅ |
| 4 | Positions and valuation (market value, P&L, exposure, limits) | ✅ |
| 4B | NAV extension: cash ledger, liabilities, fee accrual, NAV | ✅ |
| 5 | Reconciliation engine | ✅ |
| 6 | Notifications and resilience | ✅ |
| 7 | Data engineering and analytics | ✅ |
| 8 | React frontend | ✅ |
| 9 | CI/CD, quality gates, observability | ⬜ Next |
| 10 | Polish, docs, demo | ⬜ |

Detailed checklists and a progress log: [docs/roadmap.md](docs/roadmap.md).

---

## Dashboard

Run the stack and open http://localhost:3000. Demo users and their password are in [docs/manual-testing.md](docs/manual-testing.md).

| Overview | Trade blotter |
|---|---|
| ![Overview](docs/screenshots/02-overview.png) | ![Trade blotter](docs/screenshots/03-trade-blotter.png) |

| Valuation & NAV | Break detail |
|---|---|
| ![Valuation and NAV](docs/screenshots/06-valuation-nav.png) | ![Break detail](docs/screenshots/08-break-detail.png) |

---

## Architecture

Each service owns its database, exposes REST through one gateway, and talks to the others through Kafka events.

```mermaid
flowchart LR
    UI[React dashboard] -->|HTTPS + JWT| GW[API Gateway<br/>JWT check, routing]
    GW --> AUTH[auth-service]
    GW --> PF[portfolio-service]
    GW --> TR[trade-service]
    GW --> VAL[valuation-service]
    GW --> POS[position-service]
    GW --> REC[reconciliation-service]

    TR -- validates via --> PF
    TR -- outbox relay --> K[(Kafka)]
    K -- trade.created / cancelled --> POS
    POS -- position.updated --> K
    K -- position.updated --> VAL
    VAL -- valuation.calculated / limit.breached --> K
    K -- trade.settled --> REC
    MD[NSE bhavcopy, AMFI NAVs,<br/>seed closes, synthetic marks] -.-> VAL

    AUTH --- A[(auth_db)]
    PF --- P[(portfolio_db)]
    TR --- T[(trade_db)]
    VAL --- V[(valuation_db)]
    POS --- PO[(position_db)]

    EUR[Eureka registry] -.-> GW
    CFG[Config server] -.-> GW
```

| Service | Port | Responsibility | Status |
|---|---|---|---|
| `api-gateway` | 8080 | Routing, JWT validation, CORS, correlation IDs, spoof-proof user headers | ✅ |
| `discovery-server` | 8761 | Eureka service registry | ✅ |
| `config-server` | 8888 | Shared configuration | ✅ |
| `auth-service` | 8081 | Users, roles, login, token refresh and revocation | ✅ |
| `portfolio-service` | 8082 | Portfolios, funds, instrument master | ✅ |
| `trade-service` | 8083 | Trade capture, validation, lifecycle, settlement, outbox | ✅ |
| `valuation-service` | 8085 | Prices (source badges), Market Value, P&L, exposure, limits, cash, liabilities, NAV, end-of-day snapshots | ✅ |
| `position-service` | 8084 | Holdings, weighted-average cost, realized P&L, daily snapshots | ✅ |
| `reconciliation-service` | 8086 | Custodian file ingest, matching, breaks | Done |
| `notification-service` | 8087 | In-app and email notifications, open-breaks digest, dead-letter inspection and replay | ✅ |
| `analytics-service` | 8088 | Nightly ETL into the warehouse, data-quality checks, mart APIs | ✅ |

**Reliability patterns already in place:** transactional outbox (a trade and its event commit together), at-least-once
delivery with correlation IDs on every request and event, idempotent trade creation, database row locking
(`FOR UPDATE SKIP LOCKED`) so several instances can relay events or settle trades safely, and a PostgreSQL advisory
lock so two simultaneous SELLs cannot both pass the holding check. Daily position and valuation snapshots run as
**Spring Batch** jobs (one recorded run per date).

Full design: [docs/architecture.md](docs/architecture.md). Event formats: [docs/events.md](docs/events.md).

---

## Tech stack

| Layer | Technology |
|---|---|
| Language | Java 21 (LTS) |
| Framework | Spring Boot 4.0, Spring Cloud 2025.1 (Gateway, Eureka, Config) |
| Security | Spring Security 7 (OAuth2 resource server), JWT (HS256, Nimbus JOSE), BCrypt |
| Messaging | Apache Kafka 4.2 (KRaft), Spring Kafka, transactional outbox |
| Data | PostgreSQL 16 (one database per service), Spring Data JPA / JDBC, Flyway |
| API docs | springdoc-openapi (Swagger UI on every service) |
| Testing | JUnit 5, AssertJ, Mockito, MockMvc, Testcontainers (real Postgres and Kafka) |
| Build & run | Maven Wrapper (multi-module), Docker multi-stage builds, Docker Compose |
| Batch | Spring Batch 6 (end-of-day valuation and position snapshots) |
| Planned | React 18 + TypeScript, Prometheus/Grafana, GitHub Actions, SonarCloud |

---

## Data: real prices, synthetic transactions

AlphaLedger targets **Indian markets**: base currency **INR**, instruments identified by **ISIN**, and **T+1**
settlement that skips weekends and **NSE trading holidays** (a Monday 19-Oct-2026 trade settles on Wednesday, because
Tuesday is Dussehra).

- **Real public data** for prices and reference data. The demo universe is 26 real instruments:
  - **Real Estate:** listed REITs and InvITs
  - **Credit:** G-secs, NCDs and debt mutual funds
  - **Private Equity proxies:** listed equities and equity mutual funds

  Their ISINs were checked against the NSE and AMFI files. Prices come from **NSE's daily bhavcopy** (closing price)
  and **AMFI's NAVAll.txt** (mutual-fund NAVs), read by real parsers. Offline, those parsers run over a committed
  sample: the real files for 08-16 Oct 2026, filtered down to the demo instruments.
- **3 clearly labelled synthetic private companies** (identifier prefix `ZZSYN`), valued by invented quarterly marks
  (fair-value Level 3), since real private holdings have no public prices.
- **Synthetic** trades, positions, custodian files and reconciliation breaks: real ones are private, and reconciliation
  needs deliberately injected breaks to prove the matching engine.

Every price carries its **source** (e.g. `NSE_BHAVCOPY`, `AMFI_NAV`, `MFAPI`, `SYNTHETIC`) and **data date**, and is
flagged **stale** when it is older than the latest business day. A synthetic price is never used in place of a missing
real one: a thinly traded bond simply keeps its last real price, marked stale.

### Data sources and terms

| Source | Used for | How AlphaLedger uses it |
|---|---|---|
| [NSE](https://www.nseindia.com) CM bhavcopy (UDiFF) | Equities, REITs, InvITs, G-secs, bonds | NSE's [terms of use](https://www.nseindia.com/static/nse-terms-of-use) prohibit systematic or automated data collection. AlphaLedger never downloads from NSE on a schedule: files are saved by hand from a browser, and the repo holds only a filtered sample of 7 days |
| [AMFI](https://www.amfiindia.com) NAVAll.txt | Mutual-fund NAVs | One public file a day, fetched once and cached; filtered sample committed |
| [mfapi.in](https://www.mfapi.in) | Fallback for missing NAVs | Live mode only, at most 2 calls a second, behind a circuit breaker; no published terms found |
| Synthetic marks | The 3 `ZZSYN` private companies | Invented quarterly marks, labelled Level 3 |

Personal, non-commercial portfolio use only. No raw data files are committed; the full downloads stay in the
git-ignored `data/market-cache/`. Market data stays subject to each provider's terms.

---

## How NAV is calculated

```
NAV = market value + cash − liabilities
```

- **Market value:** quantity × latest price for each holding. A holding with no price is reported as unpriced, not valued at zero.
- **Cash:** the sum of an append-only ledger. A settled BUY pays `quantity × price` out on its settle date, and a settled SELL pays it in. Deposits, withdrawals and borrowings are also entries. Nothing is ever edited; a mistake is corrected with a new entry.
- **Liabilities:** management fees, expenses, payables and borrowings, counted from their accrual date until they are paid. Paying one moves it from liabilities to cash, so NAV does not change. The management fee accrues every calendar day as `annual rate × market value / 365` (1% a year by default).
- **Past dates:** cash and liabilities are rebuilt as they stood on that day, so `?asOf=` and the end-of-day snapshots are consistent.
- NAV can be **negative** when liabilities exceed assets. It is reported as it is.

Exposure and limits are still shares of market value. Three scenarios are worked out by hand in [docs/nav-scenarios.md](docs/nav-scenarios.md) and checked by unit tests.

**Limitations:** single currency (INR); no per-unit NAV, receivables, accrued income, performance fees, capital calls or distributions. Payables for unsettled purchases are not created automatically; cash moves only when a trade settles. Illiquid holdings use their latest mark, not a fair-value model.

## Running locally

Requires **JDK 21** and **Docker Desktop**. Maven is not needed: the Maven Wrapper downloads it.

```bash
./mvnw verify                                             # build all modules and run all tests
docker compose -f infra/docker-compose.yml up -d --build  # start the stack (first build takes a few minutes)
bash scripts/smoke-test.sh                                # 62 end-to-end checks through the gateway and dashboard
bash scripts/demo-trades.sh                               # optional: 20 demo trades at real 14-Oct-2026 closes
docker compose -f infra/docker-compose.yml down           # stop (add -v to also delete the Postgres data)
```

The dashboard is at **http://localhost:3000**; the step-by-step guide is [docs/manual-testing.md](docs/manual-testing.md).

The stack runs fully offline. Settings (database password, JWT signing key, demo password) can be overridden by
copying `infra/.env.example` to `infra/.env`. The full stack (16 containers) uses about 4.5 GB of memory.

| URL | What |
|---|---|
| http://localhost:8080 | API gateway: every `/api/v1/...` call |
| http://localhost:8761 | Eureka dashboard (registered services) |
| http://localhost:8090 | Kafka UI (topics and messages) |
| http://localhost:8025 | MailHog (captured emails) |
| http://localhost:8081/swagger-ui.html | auth-service API docs |
| http://localhost:8082/swagger-ui.html | portfolio-service API docs |
| http://localhost:8083/swagger-ui.html | trade-service API docs |
| http://localhost:8084/swagger-ui.html | position-service API docs |
| http://localhost:8085/swagger-ui.html | valuation-service API docs |

### Demo users

All four share the local demo password `Demo@AlphaLedger2026`.

| Email | Role | Can |
|---|---|---|
| `anita.admin@alphaledger.test` | ADMIN | Everything, including user management, manual settlement and price uploads |
| `priya.pm@alphaledger.test` | PM | Manage her portfolios (the 3 demo portfolios), capture and cancel trades, add instruments |
| `rohan.ops@alphaledger.test` | OPS_ANALYST | Read portfolios, trades and prices; upload custodian files and work breaks |
| `vikram.auditor@alphaledger.test` | AUDITOR | Read only |

Demo portfolios (managed by Priya): *AlphaLedger India Credit Opportunities Fund I*, *AlphaLedger Bharat Real Assets
Fund*, *AlphaLedger India Growth Equity Fund*.

---

## Trying the API

The easiest route is Postman: import `docs/postman/AlphaLedger.postman_collection.json`, run a **Login as ...**
request (it stores the token), then try the Portfolios, Instruments, Trades and Market data folders. With curl:

```bash
# 1. Log in as Priya (PM) and keep the access token
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"priya.pm@alphaledger.test","password":"Demo@AlphaLedger2026"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

# 2. Her portfolios and the instrument master
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/portfolios
curl -s -H "Authorization: Bearer $TOKEN" "localhost:8080/api/v1/instruments?assetClass=REAL_ESTATE"

# 3. Buy 500 units of Embassy Office Parks REIT for the Bharat Real Assets Fund (portfolio 2).
#    Trade date defaults to today, settle date to T+1 business day.
curl -s -X POST localhost:8080/api/v1/trades -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"portfolioId":2,"isin":"INE041025011","side":"BUY","quantity":500,"price":435.00}'

# 4. Its latest price, with source, data date and staleness
curl -s -H "Authorization: Bearer $TOKEN" "localhost:8080/api/v1/marketdata/prices/latest?isin=INE041025011"

# 5. A few seconds later (trade -> Kafka -> position -> Kafka -> valuation): holdings, NAV and exposure
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/portfolios/2/positions
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/portfolios/2/valuation
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/portfolios/2/exposure
```

The valuation returns `marketValue`, `cashBalance`, `totalLiabilities` and `nav` with `valuationBasis: FULL_NAV`.
Cash moves when the trade settles (T+1, or Admin's manual settle). As Rohan (ops) or Anita (admin), record cash and
liabilities:

```bash
OPS=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"rohan.ops@alphaledger.test","password":"Demo@AlphaLedger2026"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
curl -s -X POST -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  localhost:8080/api/v1/portfolios/2/cash -d '{"type":"DEPOSIT","amount":500000}'
curl -s -X POST -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  localhost:8080/api/v1/portfolios/2/liabilities -d '{"type":"EXPENSE","description":"Custody fee","amount":2500}'
```

Run `bash scripts/demo-trades.sh` to fill all three demo portfolios.

Things worth trying:
- **Rejection:** SELL more than you hold. The trade is stored as REJECTED with a reason.
- **Roles:** create a portfolio as Vikram (auditor). You get 403.
- **Events:** open Kafka UI, topic `trade.created`, to see the event for your trade.
- **Audit trail:** open `/api/v1/trades/{id}/audit` for any trade.

**Reconciliation** (after `scripts/demo-trades.sh`, once the trades have settled on 15-Oct):

```bash
python scripts/generate-custodian-file.py   # writes custodian-2026-10-15.csv with 3/3/4/4 planted breaks
OPS=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"rohan.ops@alphaledger.test","password":"Demo@AlphaLedger2026"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
curl -s -H "Authorization: Bearer $OPS" -F file=@custodian-2026-10-15.csv "localhost:8080/api/v1/recon/runs?runDate=2026-10-15"
curl -s -H "Authorization: Bearer $OPS" "localhost:8080/api/v1/recon/breaks?status=OPEN"
```

The run reports 3 missing-internal, 3 missing-external, 4 quantity and 4 price breaks. Work them with
`/api/v1/recon/breaks/{id}/assign`, `/escalate` and `/resolve` (a comment is required), and read `/audit`.

**Notifications:** each break above becomes a notification for Ops, also emailed to Rohan. Open MailHog at
<http://localhost:8025> to read the emails.

```bash
curl -s -H "Authorization: Bearer $OPS" "localhost:8080/api/v1/notifications?unreadOnly=true"
curl -s -X POST -H "Authorization: Bearer $OPS" localhost:8080/api/v1/notifications/read-all
```

**Analytics:** the warehouse loads nightly at 02:00 IST. To load now (as Anita), then read the marts:

```bash
ADMIN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"anita.admin@alphaledger.test","password":"Demo@AlphaLedger2026"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
curl -s -X POST -H "Authorization: Bearer $ADMIN" "localhost:8080/api/v1/analytics/etl-runs?fullRefresh=true"
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/analytics/aum-by-asset-class
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/analytics/dq-results
```

Also `/daily-pnl`, `/break-trend`, `/break-ageing` and `/etl-runs`. Column-level lineage is in
[docs/data-lineage.md](docs/data-lineage.md).

**Dead letters (Admin):** `GET /api/v1/ops/dead-letters/{topic}` lists records that failed after retries, with the
error. Once the cause is fixed, `POST /api/v1/ops/dead-letters/{topic}/replay` sends them back to their topic.

In Swagger, log in with `POST /api/v1/auth/login`, then paste the `accessToken` into **Authorize**.

---

## Testing

| Level | What | Tools |
|---|---|---|
| Unit | Trade state machine, T+1 and holiday rules, holding checks, position math (average cost, realized P&L, exact cancels), valuation and exposure (three hand-calculated scenarios), token issuing and rotation, ISIN check digits, price validation the synthetic-price rule, reconciliation matching (every break type, tolerance boundaries) and the break workflow | JUnit 5, AssertJ, Mockito |
| Integration (`*IT`) | Each service end to end against real PostgreSQL and Kafka: migrations, security, idempotency, duplicate events, events on the topics, Spring Batch runs | Spring Boot Test, MockMvc, Testcontainers |
| Component (frontend) | Key UI flows, including token refresh, against a mocked API | Vitest, React Testing Library, MSW |
| Smoke | 62 checks against the running Docker stack through the gateway and dashboard: health, registration, login per role, 401/403 cases, trade capture, Kafka event, prices, holidays, trade → position → valuation, custodian upload and recon roles | `scripts/smoke-test.sh` |

Currently **312 automated tests**, all passing: 278 backend (`./mvnw verify`; integration tests need Docker running) and 34 frontend (`npm test` in `frontend/`).

---

## Repository layout

| Path | Contents |
|---|---|
| `common/` | Shared library: event envelope and topics, error model, correlation IDs, JWT security, ISIN validation, business-day calendar |
| `services/` | Spring Boot services: platform (`discovery-server`, `config-server`, `api-gateway`) and business services |
| `reference-data/` | Committed reference data: instrument master (stable IDs), NSE holidays, synthetic marks, seed closes, filtered NSE/AMFI sample files |
| `infra/` | Docker Compose stack, shared service Dockerfile, Postgres init, `.env.example` |
| `scripts/` | Smoke test, demo trade loader, custodian file generator, market-data download and sample scripts |
| `docs/` | PRD, architecture, roadmap, events, conventions, Postman collection |
| `frontend/` | React dashboard, Dockerfile and nginx config |

---

## Key design decisions

Recorded as ADRs in [docs/architecture.md](docs/architecture.md) §13. Highlights:

| Decision | Why |
|---|---|
| Microservices with a database per service, Kafka between them | Mirrors real asset-management platforms; services evolve and scale independently |
| Transactional outbox for events | A trade and its event commit atomically, so no event is lost and none is sent for a rolled-back trade |
| **NAV** = market value + cash − liabilities, from an append-only cash ledger | Market value alone is not NAV; a ledger gives an audit trail and lets any past date be rebuilt |
| Real public prices, synthetic transactions; no automated NSE downloads | Credible valuations with real data, while trade and custodian data stays synthetic; NSE's terms prohibit automated collection |
| Rejected trades are stored, not just refused | The blotter and audit trail show every attempted trade, as in real trade capture |
| Positions rebuilt from all their trades on every event | Cancels are exact even after later trades, and a duplicate event can never double-count |
| Reconciliation matched in memory in one Spring Batch run, every break action audited | 10k rows in under 30 s; breaks, audit and events commit together |
| Retry, then dead-letter and move on; resilience shared in `common` | A bad message never blocks a partition, nothing is lost, and every consumer behaves the same way |
| Analytics ETL reads the service databases (read-only), not Kafka | Any past date can be re-run or backfilled; idempotent loads; tested against each service's real migrations |
| Emails queued in the database and sent by a poller | A mail-server outage delays emails but never fails event processing or loses a notification |
| Spring Boot 4 and Java 21 | The currently supported Spring line and Java LTS |
| HS256 JWTs checked at the gateway and in every service | Simple to run locally, and a service reached directly still rejects forged tokens |
| Browser refresh token only in an HttpOnly cookie; lost-response retries bound to an `Idempotency-Key`, not a grace period | Script on the page can never read the long-lived credential, and a stolen token is always detected as reuse |

---

## Documentation

| Document | What it covers |
|---|---|
| [Product requirements](docs/prd.md) | Goals, personas, functional requirements, business rules, demo data plan |
| [Architecture](docs/architecture.md) | Services, events, data pipelines, data model, APIs, security, ADRs |
| [Roadmap](docs/roadmap.md) | Phase checklists, definitions of done, progress log |
| [Browser sessions](docs/auth-session.md) | Refresh-token cookie, CSRF checks, rotation, reuse and replay rules, frontend contract |
| [UI plan](docs/ui-plan.md) | Phase 8 design system, screens against the API, milestones |
| [Manual testing](docs/manual-testing.md) | Step-by-step UI walkthrough, screenshots, presenter notes |
| [Data lineage](docs/data-lineage.md) | Warehouse layers, where every column comes from, data-quality checks |
| [NAV scenarios](docs/nav-scenarios.md) | Three NAV calculations worked out by hand |
| [Event schemas](docs/events.md) | Kafka envelope; trade, position, price, valuation and limit events |
| [Conventions](docs/conventions.md) | Branching, commits, naming for packages, topics, endpoints, tables |

---

## License and data

Code: [MIT](LICENSE). This is a personal, non-commercial portfolio project. Market data comes from public sources
(NSE, AMFI) and stays subject to their terms (see [Data sources and terms](#data-sources-and-terms)). The repository
contains only a small filtered sample, never raw data dumps. All trades, positions, custodian files and the 3 `ZZSYN` instruments are synthetic.
