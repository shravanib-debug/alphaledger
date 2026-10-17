# AlphaLedger: Roadmap & Progress Tracker

> How to use: tick items as you finish them by changing `[ ]` to `[x]`.
> Update the **Status** column in the overview table when a phase starts or completes.
> Work phases in order; each phase ends with a **Definition of Done (DoD)** that must be satisfied before moving on.

**Status legend:** ⬜ Not started | 🟨 In progress | ✅ Done

---

## Phase Overview

| # | Phase | Goal | Status |
|---|---|---|---|
| 0 | Planning & Domain Setup | Docs, domain understanding, repo, tooling | ✅ |
| 1 | Foundation & Infrastructure | Monorepo, Docker infra, Gateway, Eureka, Config | ✅ |
| 2 | Auth & Portfolio Core | Login/JWT/RBAC, portfolios and instruments | ✅ |
| 3 | Trade Capture | Trade Service, lifecycle, outbox, Kafka publish | ✅ |
| 4A | Market Data Integration | Real NSE/AMFI prices via pluggable `PriceSource`, holiday calendar and T+1, real-ISIN instrument master, offline sample data | ✅ |
| 4 | Positions & Valuation | Position consumer, prices, market value, P&L, exposure | ✅ |
| 4B | NAV Extension (Cash & Liabilities) | Cash, liabilities, accruals, true NAV = market value + cash − liabilities | ⬜ |
| 5 | Reconciliation Engine | File ingest, matching, breaks, workflow, audit | ✅ |
| 6 | Notifications & Resilience | Notifications, DLQ, retries, circuit breakers | ⬜ |
| 7 | Data Engineering & Analytics | Analytics ETL, star schema, DQ checks, views | ⬜ |
| 8 | Frontend (React) | Dashboard, blotter, positions, recon UI | ⬜ |
| 9 | CI/CD, Quality & Observability | Pipelines, coverage gate, Sonar, monitoring | ⬜ |
| 10 | Polish, Docs & Demo | README, diagrams, demo data, walkthrough | ⬜ |
| 11 | Stretch Goals (optional) | Flink/Spark, Snowflake, K8s, cloud | ⬜ |

**Suggested order note:** the React frontend (Phase 8) can be started in parallel with Phase 5 once the Trade and Position APIs are stable. Phase 4A is split: its foundation (`PriceSource`, synthetic marks, CSV upload, real seed closes, NSE holiday calendar and T+1) is done before Phase 4, which needs prices; the real NSE bhavcopy/AMFI/mfapi adapters, file cache, download scripts and full offline sample followed after Phase 5, so the end-to-end flow (trade → position → value → reconciliation) worked first. It is numbered 4A so existing phases keep their numbers.

---

## Phase 0: Planning & Domain Setup

**Goal:** Know what you're building and have a clean starting point.

- [x] Read and finalize `prd.md`
- [x] Read and finalize `architecture.md`
- [x] Learn core domain terms (trade, position, NAV, settlement, custodian, reconciliation, break)
- [x] Install tooling: JDK 21, Maven (or the Maven Wrapper), Node 18+, Docker Desktop, Git, IntelliJ/VS Code, Postman
- [x] Create GitHub repository (`alphaledger`)
- [x] Set up branching strategy (`main`, `develop`, `feature/*`)
- [x] Add `.gitignore`, `.editorconfig`, LICENSE
- [x] Create GitHub Project board or use this file as the single tracker
- [x] Decide naming conventions (packages, topics, endpoints)

**DoD:** Repo exists with docs committed; tools verified (`java -version`, `docker --version`, `node -v`).

---

## Phase 1: Foundation & Infrastructure

**Goal:** A running skeleton where services can register, route, and share config.

### Project structure
- [x] Create parent `pom.xml` with dependency management (Spring Boot 4.0, Spring Cloud 2025.1 BOM, Maven Wrapper)
- [x] Create module folders (`services/*`, `common/`, `frontend/`, `infra/`)
- [x] Create `common` module (event envelope, error model, correlation-ID filter)

### Infrastructure (Docker Compose)
- [x] PostgreSQL container with init script creating one DB per service
- [x] Kafka container (KRaft or with Zookeeper)
- [x] Kafka UI container for inspecting topics
- [x] MailHog container (for later email tests)
- [x] Verify all containers start with `docker compose up`

### Platform services
- [x] `discovery-server` (Eureka) running
- [x] `config-server` running with sample shared config
- [x] `api-gateway` running and registering with Eureka
- [x] Gateway routes defined for placeholder services
- [x] Add Actuator health endpoints to every service
- [x] Global exception handler and standard error response in `common`
- [x] Add Flyway to the services that need a DB

**DoD:** `docker compose up` starts infra; gateway, Eureka, and config server are healthy; a dummy request routes through the gateway.

---

## Phase 2: Auth & Portfolio Core

**Goal:** Secure the platform and create the master data everything else depends on.

### Auth Service
- [x] User and role entities, Flyway migration
- [x] BCrypt password hashing
- [x] `POST /auth/login` issuing JWT access + refresh tokens
- [x] `POST /auth/refresh`
- [x] Admin-only user creation endpoint
- [x] Seed default users (Admin, PM, Ops Analyst, Auditor)
- [x] Unit tests for token generation and login logic

### Gateway security
- [x] JWT validation filter at the gateway
- [x] Public routes (`/auth/**`) vs protected routes
- [x] Forward user id and roles as headers
- [x] CORS configured for the frontend origin

### Portfolio Service
- [x] Portfolio, fund, instrument entities and migrations (instrument keyed by `isin UNIQUE NOT NULL`, symbol secondary, plus `exchange`, `instrument_type`, `asset_class_proxy`, `amfi_scheme_code`, `is_synthetic`; currency default INR)
- [x] CRUD endpoints with validation
- [x] Role-based access (`@PreAuthorize`)
- [x] Seed 3 portfolios (Indian-context names, base currency INR) and the demo instruments keyed by ISIN (26 real + 3 synthetic, `prd.md` §10; initial seed, reseeded from the verified instrument master with stable `instrument_id`s in Phase 4A)
- [x] OpenAPI/Swagger enabled
- [x] Unit tests (service layer) and API tests (MockMvc)

**DoD:** Log in as each role via Postman; protected endpoints reject missing/invalid tokens; portfolios and instruments are retrievable through the gateway.

---

## Phase 3: Trade Capture

**Goal:** Reliable trade creation with a proper lifecycle and event publication.

- [x] Trade entity (with `isin`, currency INR), `trade_audit`, and `outbox_event` tables
- [x] Create trade endpoint with full validation rules (FR-TR-2)
- [x] Default settle date = trade date + 1 business day (T+1) via a `BusinessDayCalendar` interface (weekends-only implementation here; the NSE holiday calendar plugs in during Phase 4A)
- [x] Trade status state machine (`NEW → VALIDATED → SETTLED`, `REJECTED`, `CANCELLED`)
- [x] Reject invalid transitions with clear errors
- [x] Idempotent create via `tradeRef`
- [x] Trade blotter endpoint with filters and pagination
- [x] Cancel trade endpoint
- [x] Manual settle endpoint
- [x] Scheduled auto-settlement job (settle date reached; runs on business days only)
- [x] Outbox relay publishing `trade.created`, `trade.settled`, `trade.cancelled` to Kafka
- [x] Define and document event schemas
- [x] Trade audit entries written on every status change
- [x] CSV bulk trade upload (P1)
- [x] **TDD:** unit tests for state machine and validation written first
- [x] Integration test with Testcontainers: create trade → message appears on Kafka

**DoD:** Creating a trade through the gateway persists it, writes audit/outbox rows, and publishes an event visible in Kafka UI.

---

## Phase 4A: Market Data Integration

**Goal:** Replace mock prices with real public end-of-day prices (NSE bhavcopy, AMFI NAV) behind a pluggable `PriceSource`, while keeping the demo, tests, and CI fully offline. Trades, positions, and custodian files stay synthetic.

### PriceSource foundation
- [x] `PriceSource` interface, `PriceBatch`/`PriceRecord` model, and config-driven fallback order per instrument type (`architecture.md` §5.3)
- [x] `SyntheticPriceSource`: prices only `is_synthetic = true` instruments (committed quarterly marks in `reference-data/synthetic/synthetic_marks.csv`), plus seeded random-walk intraday ticks in `OFFLINE` mode only; removed from the fallback lists of all real instrument types
- [x] `CsvUploadPriceSource` wired to the existing CSV price upload
- [x] `mode` (`OFFLINE` / `LIVE`) and `network-enabled` guard; HTTP fetch throws when the guard is off
- [x] Unit tests for fallback order and gap filling
- [x] Tests: in `LIVE` mode a real instrument with no real price keeps its last real price, flagged stale, and never gets a `SYNTHETIC` price
- [x] Price API responses include `source` and `data_date` so the UI can show a source badge next to every price

### Source adapters
- [x] Save real sample files (bhavcopy for a few dates, a trimmed NAVAll.txt) as test fixtures
- [x] `NseBhavcopyParser` + `NseBhavcopyPriceSource` (UDiFF CM format, header validation, ISIN match, `ClsPric`)
- [x] Bhavcopy unit tests against saved sample files, incl. header change, unknown ISIN, zero/blank price, holiday date
- [x] `AmfiNavPriceSource` as Spring Batch job `amfiNavIngestJob` (skip category/AMC/blank lines, match either ISIN column, reject zero or non-numeric NAV)
- [x] AMFI job tests (Spring Batch Test) against the saved sample
- [x] `MfApiPriceSource` fallback adapter (`/mf/{schemeCode}`, `/latest`) with circuit breaker and rate limiter; tests with a stubbed HTTP server, never the real site
- [x] Confirm the **TO VERIFY** items in `architecture.md` §5.8 that can be checked from the real files and public pages, and update the docs (NSE terms found: automated collection prohibited, see ADR 22)
- [ ] Still open (no public source found): official meaning of NSE series codes, AMFI terms for NAVAll.txt, mfapi.in terms and limits

### Idempotency and logging
- [x] Flyway migrations: `price.source`, `price.data_date`, `price.ingestion_id`, `instrument_ref`, `holiday_calendar`, `price_ingestion_log`, `price_ingestion_error`
- [x] Raw file cache on disk (`data/market-cache/`, git-ignored) with SHA-256 checksum
- [x] Same file ingested twice → `SKIPPED_DUPLICATE`, 0 new rows (test)
- [x] `price_ingestion_log` rows with rows read/loaded/rejected and status; rejected rows in `price_ingestion_error`
- [x] Ingestion log and trigger/backfill endpoints (`/api/v1/marketdata/ingestions`)
- [x] Stale-price flag when `data_date` is earlier than the expected price date (latest business day; latest quarter-end for synthetic instruments), plus a DQ warning after `max-staleness-business-days`

### Calendar and settlement
- [x] Commit `reference-data/holidays/nse_cm_holidays_2026.csv` from NSE's published 2026 list
- [x] Load the calendar into `trade_db.holiday_calendar` and `valuation_db.holiday_calendar`
- [x] Trade Service: `BusinessDayCalendar` uses the holiday table for T+1 settle dates and the auto-settlement job
- [x] **TDD:** T+1 tests for weekday, Friday → Monday, and holiday (trade 19-Oct-2026 → settle 21-Oct-2026, because 20-Oct-2026 is Dussehra)

### Reference data and offline sample
- [x] Build `reference-data/instruments/instrument_master.csv` with real ISINs (from NSE `EQUITY_L.csv`, bhavcopy, NAVAll.txt) plus 3 synthetic `ZZSYN…` instruments; reseed portfolio-service and valuation-service from it
- [x] Add a stable `instrument_id` column to the instrument master; seed `portfolio_db.instrument(id)` and `valuation_db.instrument_ref(instrument_id)` from it so IDs are identical across services (test: same ID for every ISIN in both services); events keep `isin` as the fallback join key
- [x] Re-check every demo ISIN against the latest source files before seeding
- [x] `scripts/market-data/download.sh` (polite, cached, one request per file) and `make-sample.sh` (filter to demo ISINs)
- [x] Commit the small filtered sample (`reference-data/sample/nse/`, `reference-data/sample/amfi/`) covering the demo window (08-16 Oct 2026)
- [x] Trade generator uses real historical closes from the sample for synthetic trade prices (`scripts/demo-trades.sh` books at the 14-Oct closes, identical to the committed bhavcopy sample)
- [x] Tests run with `mode=OFFLINE` and `network-enabled=false`; the root pom routes every non-local HTTP(S) request in test JVMs to a dead proxy, so a test that reaches for the internet fails (checked by `FallbackOrderTest`). CI itself arrives in Phase 9
- [x] README: data sources, terms-of-use notes per source, and "personal/portfolio use only; no raw data in the repo"

**DoD:** With the network disabled, `docker compose up` loads real prices for every demo ISIN from the committed sample, synthetic instruments get quarterly marks, re-ingesting the same file loads 0 rows, the ingestion log shows loaded/rejected counts, T+1 settlement skips weekends and NSE holidays, and CI is green without any external call.

---

## Phase 4: Positions & Valuation

**Goal:** Turn trades into holdings and holdings into portfolio value.

### Position Service
- [x] Position entity, snapshot, and `processed_event` tables
- [x] Kafka consumer for `trade.created` and `trade.cancelled`
- [x] Weighted-average cost logic for BUY
- [x] Realized P&L logic for SELL
- [x] Reject/flag SELL beyond holdings
- [x] Idempotent processing (duplicate event ignored)
- [x] Publish `position.updated`
- [x] Positions query endpoint
- [x] Daily position snapshot job (Spring Batch)
- [x] **TDD:** unit tests covering buy, partial sell, full sell, duplicate event, cancel
- [x] Integration test: event in → position row correct

### Valuation Service
- [x] Price (with `source`, `data_date`), valuation snapshot (market value, with nullable NAV columns for Phase 4B), exposure snapshot, `limit_rule` tables
- [x] Scheduled price ingestion through the `PriceSource` (Phase 4A) publishing `price.updated`; `SyntheticPriceSource` for local intraday moves (`OFFLINE` mode only) instead of a standalone mock feed
- [x] CSV price upload endpoint
- [x] Consume `position.updated` (prices are read from valuation-service's own price table at valuation time, so it does not consume its own `price.updated`; that topic is for other consumers)
- [x] Calculate portfolio market value and unrealized P&L (label it "Market Value", not NAV)
- [x] Exposure by asset class and instrument
- [x] End-of-day valuation snapshot Spring Batch job (`valuation_basis = MARKET_VALUE_ONLY`)
- [x] Valuation and valuation-history endpoints
- [x] Limit rules and `limit.breached` event (P1)
- [x] Unit and integration tests

**DoD:** Create trades → positions update → market value and exposure reflect them; numbers verified manually against a spreadsheet for at least 3 scenarios.

---

## Phase 4B: NAV Extension (Cash & Liabilities)

**Goal:** Upgrade "market value" to a defensible NAV (`NAV = market value + cash − liabilities`). Recommended before the demo; can run in parallel with Phase 5.

- [ ] Flyway migrations: `cash_balance`, `cash_transaction` (append-only), `liability`
- [ ] valuation-service consumes `trade.settled` (idempotent): BUY reduces cash, SELL increases cash
- [ ] Deposit/withdrawal endpoints (`/portfolios/{id}/cash`)
- [ ] Liability CRUD endpoints (`/portfolios/{id}/liabilities`): accrued fees, expenses, payables, borrowings
- [ ] Daily fee accrual job (e.g., `fee_rate × market_value / 365`) writing to `liability`
- [ ] Compute `NAV = market value + cash − liabilities` and store components plus `valuation_basis = FULL_NAV`
- [ ] Include NAV components in the valuation API response
- [ ] **TDD:** unit tests for cash-only, liabilities-only, mixed, and liabilities-exceed-assets (negative NAV) cases
- [ ] Verify three scenarios against a hand-built spreadsheet
- [ ] Switch UI labels from "Market Value" to "NAV" (coordinate with Phase 8)
- [ ] Document the formula and its limitations in the README
- [ ] Stretch: per-unit NAV (units outstanding), fair-value level tag, valuation override

**DoD:** NAV on the dashboard equals market value + cash − liabilities for the seeded portfolios, and the breakdown of each component is visible.

---

## Phase 5: Reconciliation Engine

**Goal:** The signature feature: ingest, match, detect, and manage breaks.

### Data and ingestion
- [x] Reconciliation tables (`recon_run`, `custodian_record`, errors, `recon_break`, audit)
- [x] Consume `trade.settled` into `settled_trade_view`
- [x] Write `generate-custodian-file.py` creating a CSV (instruments by ISIN) with injected breaks
- [x] File upload endpoint (multipart) with checksum duplicate protection
- [x] Spring Batch job: reader → validator → staging writer
- [x] Invalid rows captured in `custodian_record_error`

### Matching engine
- [x] Define match key (`trade_ref + instrument + trade_date`, instrument keyed by ISIN)
- [x] Implement exact quantity comparison
- [x] Implement price comparison with configurable tolerance
- [x] Detect `MISSING_INTERNAL`
- [x] Detect `MISSING_EXTERNAL`
- [x] Detect `QTY_MISMATCH`
- [x] Detect `PRICE_MISMATCH`
- [x] Run summary (total, matched, per-type break counts, match rate)
- [x] **TDD:** unit tests for every break type and boundary tolerance cases
- [x] Idempotency: re-running the same file creates no duplicates
- [x] Performance test: 10,000 rows in under 30 seconds

### Break workflow
- [x] Break list endpoint with filters (status, type, run, age)
- [x] Assign break to analyst
- [x] Status transitions `OPEN → IN_REVIEW → RESOLVED / ESCALATED`
- [x] Resolution actions (accept external, adjust internal, known difference, escalate) with mandatory comment
- [x] Immutable `break_action_audit` entries
- [x] Publish `recon.break.created` and `recon.break.resolved` via outbox
- [x] Break ageing calculation (P1)
- [x] Integration tests end-to-end (upload file → breaks created → resolve)

**DoD:** Uploading the seeded custodian file produces exactly the injected breaks (3/3/4/4); each can be assigned and resolved with a full audit trail.

---

## Phase 6: Notifications & Resilience

**Goal:** Make the system communicate and survive failures.

### Notification Service
- [ ] Notification entity and API (list, mark read)
- [ ] Consume `recon.break.created` and `limit.breached`
- [ ] Idempotent consumer
- [ ] Email notifications via MailHog (P1)
- [ ] Daily open-breaks digest (P2)

### Resilience
- [ ] Spring Kafka `DefaultErrorHandler` with exponential backoff on all consumers
- [ ] Dead-letter topics for every consumed topic
- [ ] Endpoint or script to replay DLQ messages
- [ ] Resilience4j circuit breaker/retry on gateway routes and any REST calls
- [ ] Rate limiting at the gateway (P2)
- [ ] Failure-injection tests (kill a consumer, send a poison message, verify recovery)
- [ ] Correlation ID propagated through HTTP and Kafka headers

**DoD:** A poison message lands in a DLQ without blocking the consumer; stopping and restarting a service loses no events.

---

## Phase 7: Data Engineering & Analytics

**Goal:** Build the analytics pipeline and demonstrate data engineering skills.

### Warehouse model
- [ ] Create `analytics_db` with staging, core, and mart layers
- [ ] Build `dim_date`, `dim_portfolio`, `dim_instrument` (with `isin`, `exchange`)
- [ ] Build `fact_trade`, `fact_position_daily`, `fact_valuation_daily`, `fact_recon_break`

### ETL pipeline
- [ ] Spring Batch extract jobs from source databases/Kafka into staging
- [ ] Transform and load into star schema (incremental by date)
- [ ] Nightly scheduler
- [ ] Re-runnable and idempotent loads

### Data quality
- [ ] Row-count reconciliation (source vs target)
- [ ] Null, uniqueness, and referential checks
- [ ] Sanity checks (market value ≥ 0, no future dates; NAV may be negative if liabilities exceed assets)
- [ ] Store results in `dq_check_result`; fail job on critical errors

### Analytical views and APIs
- [ ] `vw_aum_by_asset_class`
- [ ] `vw_daily_pnl`
- [ ] `vw_break_trend`
- [ ] `vw_break_ageing`
- [ ] Analytics REST endpoints
- [ ] Data lineage documented

**DoD:** Nightly job populates the warehouse, DQ checks pass, and the four views return correct data for the seeded dataset.

---

## Phase 8: Frontend (React)

**Goal:** A clean, role-aware dashboard for PMs and Ops Analysts.

### Setup
- [ ] Scaffold React + Vite (+ TypeScript) app
- [ ] Choose UI library and set up theme/layout
- [ ] Axios client with JWT interceptor and refresh handling
- [ ] Routing with protected routes and role-based menus

### Screens
- [ ] Login page
- [ ] Portfolio dashboard (Market Value / NAV, P&L cards, allocation pie, valuation trend line)
- [ ] Trade blotter (table, filters, pagination, status badges)
- [ ] Create-trade form with validation
- [ ] Positions page per portfolio, with a source badge (`source` + `data_date`) next to every price and a stale indicator
- [ ] Reconciliation page (upload file, run summary, break list with filters)
- [ ] Break detail page (internal vs external comparison, assign, resolve, escalate, audit history)
- [ ] Notifications bell and list
- [ ] Analytics page (break trend, AUM by asset class)

### Quality
- [ ] Loading, empty, and error states on every screen
- [ ] Responsive layout
- [ ] Component tests (Vitest + React Testing Library) for key flows
- [ ] Dockerfile + nginx config for the frontend

**DoD:** A user can log in, create a trade, see market value change, upload a custodian file, and resolve a break, entirely from the UI.

---

## Phase 9: CI/CD, Quality & Observability

**Goal:** Make the project look and behave like a professional team's codebase.

### CI/CD
- [ ] Multi-stage Dockerfile for every service
- [ ] GitHub Actions workflow: build + unit tests on every PR
- [ ] Integration test stage (Testcontainers)
- [ ] JaCoCo coverage report with **80% gate**
- [ ] SonarCloud integration and quality gate
- [ ] Checkstyle / SpotBugs
- [ ] Docker image build and push (tag by commit SHA)
- [ ] Deploy stage for `main` (compose on a VM or similar)
- [ ] Branch protection: require passing CI before merge
- [ ] PR template and code-review checklist

### Observability
- [ ] Prometheus scraping all services
- [ ] Grafana dashboards (JVM, HTTP latency, Kafka consumer lag)
- [ ] Business metrics (trades/day, break rate, DLQ count)
- [ ] Structured JSON logging with correlation IDs
- [ ] Optional: centralized logs (Loki/ELK)

### Compose polish
- [ ] Compose profiles (`core`, `recon`, `analytics`, `monitoring`)
- [ ] Health checks and `depends_on` conditions
- [ ] `.env.example` for configuration

**DoD:** A PR triggers a green pipeline with coverage and Sonar results; Grafana shows live metrics.

---

## Phase 10: Polish, Docs & Demo

**Goal:** Make it presentable to recruiters and interviewers.

- [ ] Professional `README.md` (overview, architecture diagram, setup in 3 commands, screenshots)
- [ ] Final architecture and sequence diagrams exported to `docs/diagrams`
- [ ] Postman collection and OpenAPI docs for every service
- [ ] Final demo dataset and scripts (`seed-data`, custodian file generator, market-data download and sample scripts)
- [ ] Add a "Data sources and licensing" section to the README (NSE bhavcopy, AMFI NAVAll.txt, mfapi.in, yfinance; what each is used for; terms-of-use notes; personal/portfolio use only; repo holds a download script and a small filtered sample, never raw dumps; no paid broker APIs)
- [ ] End-to-end test script (`e2e-test.sh`)
- [ ] Record a 3 to 5 minute demo video or GIF walkthrough
- [ ] Write a short "design decisions and trade-offs" section
- [ ] Add a "Known simplifications" section to the README (market value vs full NAV, single currency INR, no short selling, real EOD prices but synthetic trades/positions/custodian files/breaks, T+1 for all instrument types, trading holidays used as settlement holidays, no corporate actions, synthetic Level 3 quarterly marks)
- [ ] Prepare interview notes: 60-second pitch, 5-minute deep dive, answers to likely questions (why Kafka, outbox pattern, idempotency, how recon works, how you tested)
- [ ] Add project to resume and LinkedIn with measurable bullets (e.g., "reconciled 10k records in under 30 s", "85% test coverage")
- [ ] Final code cleanup, remove dead code and TODOs

**DoD:** A stranger can clone the repo, run the stack, and follow the README to see the full demo.

---

## Phase 11: Stretch Goals (Optional)

Pick based on remaining time and the JD's "add-on" skills.

- [ ] Flink job: real-time exposure and P&L windows
- [ ] Spark job replacing the analytics ETL
- [ ] Load analytics data into a real Snowflake trial account
- [ ] Schema Registry with Avro event contracts
- [ ] OpenTelemetry distributed tracing
- [ ] Kubernetes manifests/Helm chart, run on kind/minikube
- [ ] Deploy to AWS/Azure (ECS/EKS, RDS, managed Kafka) with Terraform
- [ ] Multi-currency and FX conversion
- [ ] Performance fee / carried-interest waterfall and capital call/distribution handling
- [ ] Digital Distribution Intelligence module (AUM by channel, region, distributor)
- [ ] WebSocket live updates on the dashboard
- [ ] Python microservice (e.g., price feed or analytics) to show Java + Python
- [ ] yfinance (`.NS` / `.BO`) last-resort fallback script writing a CSV for `CsvUploadPriceSource`
- [ ] BSE bhavcopy adapter as a second exchange source

---

## Cross-Phase Checklists

### Every service must have (Definition of Done for any service)
- [ ] Layered structure (controller, service, repository, domain, dto)
- [ ] Input validation and consistent error responses
- [ ] Flyway migrations
- [ ] Actuator health + metrics
- [ ] OpenAPI documentation
- [ ] Unit tests and integration tests
- [ ] Dockerfile and Compose entry
- [ ] Correlation ID logging
- [ ] README snippet (purpose, endpoints, events)

### Risks to watch
- [ ] Don't start P1/P2 items before the phase's P0 items are done
- [ ] Commit small and often; one feature per branch
- [ ] If a phase runs long, cut scope within it rather than skipping the DoD

---

## Progress Log

| Date | Phase | Notes |
|---|---|---|
| 2026-10-15 | 0 | Repo initialised with docs, `.gitignore`, `.gitattributes`, `.editorconfig`, MIT LICENSE, README, `docs/conventions.md`; `main` and `develop` pushed. This file is the single tracker. Tooling: Java 23, Node 24, Docker 29, Git 2.49 present; Maven not installed (plan: Maven Wrapper `mvnw` in Phase 1, build targets Java 17 via `--release 17`). Domain-terms review still open. |
| 2026-10-15 | 0 | Decided on Java 21 (LTS) instead of 17 (ADR 12): docs and conventions updated; build will use `maven.compiler.release=21` and `eclipse-temurin:21` images. JDK 21 still to install (Java 23 is present). |
| 2026-10-15 | 0 | JDK 21 installed (Temurin 21.0.12.1); `JAVA_HOME` and PATH point to it, Java 23 kept side by side. Maven comes from the Maven Wrapper in Phase 1. Remaining Phase 0 item: domain-terms review. |
| 2026-10-15 | 0 | Phase 0 complete (DoD met: repo with docs on GitHub; `java -version` 21, `docker --version` 29, `node -v` 24). |
| 2026-10-15 | 1 | Phase 1 complete. Spring Boot 4.0.8 + Spring Cloud 2025.1.3 (ADR 13), Maven Wrapper 3.9.16, 13 modules (`common`, 3 platform services, 8 business-service skeletons with Actuator, Flyway baseline, Eureka and config clients). `infra/docker-compose.yml`: Postgres 16 (8 DBs), Kafka 4.2 KRaft, Kafka UI, MailHog, Eureka, config server, gateway, portfolio-service. `./mvnw verify`: 34 tests green. `scripts/smoke-test.sh`: 8/8 checks pass (health, shared config, Eureka registration, gateway routing, correlation ID). |
| 2026-10-15 | 2 | Phase 2 complete. auth-service: users/roles (`app_user`, `role`, `refresh_token`), BCrypt, HS256 access tokens (15 min) + rotating opaque refresh tokens (7 days, reuse revokes all), login/refresh/logout/me, Admin-only user management, 4 demo users (IDs 1-4). Gateway: JWT validation, spoof-proof `X-User-*` headers, JSON 401/403. portfolio-service: portfolios, funds, instruments (ISIN check-digit validation), PM-owns-portfolio rules, `@PreAuthorize`, instrument master `reference-data/instruments/instrument_master.csv` loaded with stable IDs 1-29 (pulled forward from Phase 4A), 3 demo portfolios with 7 funds. OpenAPI + Swagger UI on auth and portfolio. Postman collection in `docs/postman/`. `./mvnw verify`: 91 tests (incl. Testcontainers ITs) green; smoke test 22/22. ADR 14 (HS256 shared secret). |
| 2026-10-15 | 3 | Phase 3 complete. trade-service: capture with validation (dates on business days, T+1 default via `BusinessDayCalendar`, weekends-only until 4A), portfolio/instrument checks via portfolio-service (ADR 15), SELL-above-holding check under a Postgres advisory lock, REJECTED trades stored with reasons (ADR 16), idempotent `tradeRef`, blotter, cancel (guards dependent SELLs), Admin manual settle, auto-settlement job (market time zone, SKIP LOCKED), `trade_audit` on every change, transactional outbox + relay to `trade.created`/`trade.settled`/`trade.cancelled`, CSV upload. Event schemas in `docs/events.md`. Tests: 28 unit + 9 Testcontainers ITs (Postgres + Kafka); smoke test 27/27. Fixed: Eureka client picking up the load-balanced RestClient builder. |
| 2026-10-15 | 4A | Phase 4A foundation done (rest deferred until after Phase 5, ADR 17). NSE CM 2026 holiday calendar committed and loaded into trade_db and valuation_db; T+1 now skips NSE holidays (19-Oct trade settles 21-Oct, Dussehra). valuation-service: `PriceSource` interface + registry (startup fails if SYNTHETIC is configured for a real instrument type), `SyntheticPriceSource` (L3 quarterly marks; OFFLINE-only intraday ticks, off by default), CSV price upload with declared provenance, real seed closes for all 26 instruments (14-Oct-2026, from NSE bhavcopy and AMFI NAVAll), price table with source/data_date and partial unique indexes, ingestion log with checksum idempotency and rejected rows, latest price with source badge and staleness. Reference data packaged in the common jar. Tests: common 34, valuation 10 unit + 6 ITs; smoke test 32/32. |
| 2026-10-16 | 4 | Phase 4 complete. position-service: positions rebuilt from applied trades on every `trade.created`/`trade.cancelled` (weighted-average cost, realized P&L, exact cancels, trade-date order, SELL above holding skipped and recorded), idempotent consumer (`processed_event`), `position.updated` via outbox, positions/snapshots/exceptions endpoints, daily snapshot as a Spring Batch job. valuation-service: holdings copy from `position.updated` (newer update wins), live Market Value, unrealized/realized P&L and exposure by asset class and instrument with each line's price source/date/staleness, unpriced holdings reported not zero-valued, end-of-day Spring Batch job writing immutable `valuation_snapshot` + `exposure_snapshot` (basis MARKET_VALUE_ONLY, nav NULL), `valuation.calculated`, limit rules (portfolio overrides global) and once-per-day `limit.breached`, `price.updated` published on ingestion. Outbox/consumer helpers moved to common. DoD: three hand-calculated scenarios as unit tests; `scripts/demo-trades.sh` books 20 real-price trades. Smoke test 38/38. |
| 2026-10-17 | 5 | Phase 5 complete. reconciliation-service: `trade.settled` consumed idempotently into `settled_trade_view`; multipart custodian upload (Admin/Ops) with header check, SHA-256 checksum and `UNIQUE(run_date, source, file_checksum)` so a re-upload returns the same run (200); raw file stored; Spring Batch job `reconciliationJob` (stage step: reader → row validator → batch writer, chunk 500; match step) with bad rows in `custodian_record_error`; hash-map matching engine on `trade_ref + isin + trade_date` (signed quantity exact, price tolerance ₹0.01 from config) detecting all four break types; run summary with per-type counts and match rate; break workflow OPEN → IN_REVIEW → ESCALATED/RESOLVED with mandatory comments, row locks, immutable `break_action_audit` (before/after JSON), ageing and SLA-overdue flag; `recon.break.created`/`recon.break.resolved` via outbox. PMs have no recon access. `scripts/generate-custodian-file.py` plants 3/3/4/4 breaks in the demo book. DoD met in `ReconciliationIT` (exactly 3/3/4/4; a break worked to resolution with 4 audit entries); 10k rows reconcile in under 30 s. Tests: 194 total; smoke test 42/42. |
| 2026-10-17 | 4A | Phase 4A complete. Real files downloaded once (NSE bhavcopy 08-16 Oct 2026, AMFI NAVAll 16-Oct) and filtered to the demo ISINs into `reference-data/sample/nse|amfi` by `make-sample.sh`. `NseBhavcopyParser`/`NseBhavcopyPriceSource` (header check, `ClsPric`, block-deal `BL` rows ignored and `EQ` preferred, bad rows rejected), `AmfiNavPriceSource` as Spring Batch job `amfiNavIngestJob`, `MfApiPriceSource` fallback with Resilience4j circuit breaker and rate limiter (LIVE + network only), one guarded HTTP client, raw-file cache, FAILED log entry when a source has no file, backfill endpoint, OFFLINE startup runs the real parsers over the sample (every demo instrument gets a real 16-Oct price; the thinly traded PFC bond keeps its 14-Oct price, flagged stale). NSE's terms prohibit automated collection, so NSE files are never downloaded on a schedule (ADR 22); `download.sh` only fetches AMFI unless `NSE_MANUAL_FETCH=yes`. Daily price run moved to 23:30 IST (AMFI NAVs arrive by about 23:00), EOD valuation to 23:45. Test JVMs cannot reach external hosts. Tests: 220 total. |
