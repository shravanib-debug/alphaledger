# AlphaLedger

Portfolio management and trade reconciliation platform for Indian markets: an event-driven Java/Spring Boot microservices system where trades flow in, positions and portfolio market value update automatically, and settled trades are reconciled against a custodian file, with breaks managed in a React dashboard.

**Status:** Phase 0 (planning and setup). See the [roadmap](docs/roadmap.md).

## Documents
- [Product requirements](docs/prd.md)
- [Architecture, tech stack and data pipelines](docs/architecture.md)
- [Roadmap and progress tracker](docs/roadmap.md)
- [Conventions](docs/conventions.md)

## Data
Prices and instrument reference data come from public end-of-day sources (NSE bhavcopy, AMFI NAVs). Trades, positions, custodian files and reconciliation breaks are synthetic. Personal, non-commercial portfolio project; no raw market data is committed to this repository.

## License
Code: [MIT](LICENSE). Third-party market data stays under its source's terms.
