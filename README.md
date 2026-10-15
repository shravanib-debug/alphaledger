# AlphaLedger

Portfolio management and trade reconciliation platform for Indian markets: an event-driven Java/Spring Boot microservices system where trades flow in, positions and portfolio market value update automatically, and settled trades are reconciled against a custodian file, with breaks managed in a React dashboard.

**Status:** Phase 1 (foundation and infrastructure) complete. See the [roadmap](docs/roadmap.md).

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

Local credentials and ports can be overridden by copying `infra/.env.example` to `infra/.env`.

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

## License
Code: [MIT](LICENSE). Third-party market data stays under its source's terms.
