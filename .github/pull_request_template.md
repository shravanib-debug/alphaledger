## What and why


## How it was tested


## Review checklist
- [ ] `./mvnw verify` passes (tests, coverage gate, Checkstyle, SpotBugs)
- [ ] `npm run lint`, `npm run build` and `npm test` pass in `frontend/` if it changed
- [ ] Migrations are new Flyway files; existing ones are unchanged
- [ ] Events changed? `docs/events.md` updated
- [ ] No secrets, and no raw market-data files
- [ ] Roadmap and README updated if a milestone moved
