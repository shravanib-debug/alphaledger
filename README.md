# AlphaLedger

Portfolio management and trade reconciliation platform for Indian markets: an event-driven Java/Spring Boot microservices system where trades flow in, positions and portfolio market value update automatically, and settled trades are reconciled against a custodian file, with breaks managed in a React dashboard.

**Status:** Phase 3 (trade capture, outbox and Kafka events) complete; Phase 4A foundation (prices with source badges, NSE holiday calendar, T+1) done. See the [roadmap](docs/roadmap.md).

## Running locally

Requires JDK 21 and Docker Desktop. Maven is not needed: the Maven Wrapper (`./mvnw`) downloads it.

```bash
./mvnw verify                                           # build all modules and run the tests
docker compose -f infra/docker-compose.yml up -d --build  # start the local stack
bash scripts/smoke-test.sh                              # check health, registration and gateway routing
docker compose -f infra/docker-compose.yml down         # stop (add -v to also delete the Postgres data)
```

| URL | What |
|---|---|
| http://localhost:8080 | API gateway (all `/api/v1/...` calls) |
| http://localhost:8761 | Eureka service registry dashboard |
| http://localhost:8888/portfolio-service/default | Config server (shared config as seen by a service) |
| http://localhost:8090 | Kafka UI |
| http://localhost:8025 | MailHog (captured emails) |
| http://localhost:8081/swagger-ui.html | auth-service API docs |
| http://localhost:8082/swagger-ui.html | portfolio-service API docs |
| http://localhost:8083/swagger-ui.html | trade-service API docs |
| http://localhost:8085/swagger-ui.html | valuation-service API docs (market data) |

Local credentials and ports can be overridden by copying `infra/.env.example` to `infra/.env`.

### Demo users

All four share the local demo password `Demo@AlphaLedger2026` (set by `DEMO_USER_PASSWORD`).

| Email | Role | Can |
|---|---|---|
| `anita.admin@alphaledger.test` | ADMIN | Everything, including user management |
| `priya.pm@alphaledger.test` | PM | Manage her portfolios (the 3 demo portfolios), add instruments |
| `rohan.ops@alphaledger.test` | OPS_ANALYST | Read portfolios and instruments (reconciliation from Phase 5) |
| `vikram.auditor@alphaledger.test` | AUDITOR | Read only |

```bash
curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"priya.pm@alphaledger.test","password":"Demo@AlphaLedger2026"}'
# then: curl -H "Authorization: Bearer <accessToken>" localhost:8080/api/v1/portfolios
```

Or import `docs/postman/AlphaLedger.postman_collection.json` into Postman and run a "Login as ..." request first.

### Capturing a trade

As Priya (PM), buy 500 units of Embassy Office Parks REIT for the Bharat Real Assets Fund (portfolio 2). Trade date
defaults to today and settle date to T+1 business day:

```bash
curl -s -X POST localhost:8080/api/v1/trades -H "Authorization: Bearer <accessToken>" -H 'Content-Type: application/json' \
  -d '{"portfolioId":2,"isin":"INE041025011","side":"BUY","quantity":500,"price":435.00}'
```

The trade is stored as VALIDATED (or REJECTED with a `rejectionReason`, e.g. a SELL above the holding), audited, and a
`trade.created` event appears on Kafka (see Kafka UI, topic `trade.created`). Event formats: [docs/events.md](docs/events.md).

## Repository layout

| Path | Contents |
|---|---|
| `common/` | Shared library: event envelope, error model, correlation IDs |
| `services/` | Spring Boot services: platform (`discovery-server`, `config-server`, `api-gateway`) and business services |
| `infra/` | Docker Compose stack, shared service Dockerfile, Postgres init scripts |
| `scripts/` | Smoke test (more scripts arrive with later phases) |
| `frontend/` | React dashboard (Phase 8) |
| `docs/` | PRD, architecture, roadmap, conventions |

## Documents
- [Product requirements](docs/prd.md)
- [Architecture, tech stack and data pipelines](docs/architecture.md)
- [Roadmap and progress tracker](docs/roadmap.md)
- [Conventions](docs/conventions.md)

## Data
Prices and instrument reference data come from public end-of-day sources (NSE bhavcopy, AMFI NAVs). Trades, positions, custodian files and reconciliation breaks are synthetic. Personal, non-commercial portfolio project; no raw market data is committed to this repository.

Today the demo uses a committed seed of real closes for 14-Oct-2026 (26 instruments, filtered by hand from that
day's NSE bhavcopy and AMFI NAVAll) plus invented quarterly marks for the 3 clearly labelled synthetic private
instruments. Every price carries its source and data date, and is flagged stale when older than the expected date:

```bash
curl -s -H "Authorization: Bearer <accessToken>" "localhost:8080/api/v1/marketdata/prices/latest?isin=INE041025011"
```

Settlement is T+1 on business days: weekends and NSE trading holidays (`reference-data/holidays/`) are skipped, so a
trade on Monday 19-Oct-2026 settles on Wednesday 21-Oct (Tuesday is Dussehra). Live NSE/AMFI downloads arrive later
(roadmap Phase 4A, deferred part).

## License
Code: [MIT](LICENSE). Third-party market data stays under its source's terms.
