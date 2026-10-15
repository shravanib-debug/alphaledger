# AlphaLedger: Conventions

> Decided in Phase 0. Applies to every service, script, and document. Change it through a PR that updates this file.

## 1. Git workflow

| Branch | Purpose | Rules |
|---|---|---|
| `main` | Stable, demo-ready | Only merges from `develop` (release) or `hotfix/*`. Protected once CI exists (Phase 9). |
| `develop` | Integration branch | Default target for feature PRs. Must build. |
| `feature/<phase>-<short-name>` | One feature or checklist group | Branched from `develop`, e.g. `feature/p3-trade-state-machine`, `feature/p4a-bhavcopy-parser`. |
| `fix/<short-name>` | Bug fix on `develop` | |
| `hotfix/<short-name>` | Urgent fix on `main` | Merged back into `develop`. |
| `docs/<short-name>` | Documentation-only change | |

- **Commits:** [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`. Types: `feat`, `fix`, `docs`, `test`, `refactor`, `chore`, `build`, `ci`, `perf`. Scope is the service or area: `feat(trade): default settle date to T+1`, `test(marketdata): bhavcopy header change`.
- **PRs:** small, one feature per branch; title in Conventional Commit form; link the roadmap phase; tick the roadmap item in the same PR.
- **Tracker:** `docs/roadmap.md` is the single progress tracker (no separate project board). Update the checkbox, the phase status, and the Progress Log.

## 2. Java

| Item | Convention | Example |
|---|---|---|
| Group / base package | `com.alphaledger` | |
| Service package | `com.alphaledger.<service>` (no hyphens) | `com.alphaledger.trade`, `com.alphaledger.valuation` |
| Shared library | `com.alphaledger.common` | event envelope, error model |
| Market data code | `com.alphaledger.valuation.marketdata` | `PriceSource`, `NseBhavcopyParser` |
| Maven artifactId | service folder name | `trade-service`, `valuation-service` |
| Layers | `controller`, `service`, `repository`, `domain`, `dto`, `mapper`, `event`, `config`, `exception`, `util` | `architecture.md` §2.1 |
| Classes | `<Thing>Controller`, `<Thing>Service`, `<Thing>Repository`, `<Thing>Request` / `<Thing>Response` DTOs, `<Thing>Event` | `TradeController`, `CreateTradeRequest`, `TradeCreatedEvent` |
| Tests | `<ClassUnderTest>Test` (unit), `<ClassUnderTest>IT` (integration, Testcontainers) | `PositionCalculatorTest`, `TradeOutboxIT` |
| Money and prices | `BigDecimal`, never `double`; amounts in INR | |
| Dates | `LocalDate` for trade/settle/data dates; `Instant` (UTC) for timestamps | |
| Language level | Java 21 (`maven.compiler.release=21`), even when built on a newer JDK | |

## 3. Kafka topics and events

| Item | Convention | Example |
|---|---|---|
| Topic name | `<aggregate>.<past-tense-event>`, lowercase, dot-separated | `trade.created`, `price.updated`, `recon.break.resolved` |
| Dead-letter topic | `<topic>.DLT` | `trade.created.DLT` |
| Event type (envelope) | UPPER_SNAKE of the topic | `TRADE_CREATED` |
| Payload fields | camelCase JSON | `tradeRef`, `instrumentId`, `isin`, `settleDate` |
| Instrument identity | Events carry both `instrumentId` and `isin`; ISIN is the fallback join key | |
| Versioning | `eventVersion` in the envelope; additive changes only within a version | |

## 4. REST API

| Item | Convention | Example |
|---|---|---|
| Base path | `/api/v1/...` through the gateway | |
| Resources | plural nouns, kebab-case | `/api/v1/portfolios/{id}/positions`, `/api/v1/marketdata/ingestions` |
| Actions | `POST` on a sub-resource verb only for state transitions | `POST /api/v1/trades/{id}/cancel` |
| Query params | camelCase | `?portfolioId=&from=&to=` |
| JSON fields | camelCase | |
| Errors | Standard error format (`architecture.md` §7.2) | |
| Pagination | `page`, `size`, `sort` | |

## 5. Database

| Item | Convention | Example |
|---|---|---|
| Database per service | `<service>_db` | `trade_db`, `valuation_db` |
| Tables and columns | snake_case, singular table names | `trade`, `price_ingestion_log`, `settle_date` |
| Primary key | `id` (`BIGINT`); seeded instruments use the `instrument_id` from `reference-data/instruments/instrument_master.csv` | |
| Flyway migrations | `V<n>__<description>.sql`; repeatable reference loads `R__<description>.sql` | `V3__add_price_source_and_data_date.sql` |
| Enum values | UPPER_SNAKE stored as text | `MARKET_VALUE_ONLY`, `NSE_BHAVCOPY` |

## 6. Frontend

| Item | Convention |
|---|---|
| Components | PascalCase files (`BreakDetail.tsx`) |
| Hooks | `useThing.ts` |
| Folders | feature-based: `src/features/<feature>/` |
| API client | one module per backend service under `src/api/` |

## 7. Configuration and files

| Item | Convention | Example |
|---|---|---|
| App properties | `alphaledger.<area>.<key>` in kebab-case | `alphaledger.marketdata.network-enabled` |
| Environment variables | `ALPHALEDGER_<AREA>_<KEY>` (Spring relaxed binding) | `ALPHALEDGER_MARKETDATA_MODE=OFFLINE` |
| Reference data | `reference-data/<kind>/` | `reference-data/holidays/nse_cm_holidays_2026.csv` |
| Scripts | kebab-case | `scripts/market-data/download.sh` |
| Trade refs | `TRD-<yyyyMMdd>-<seq4>` | `TRD-20261019-0001` |
