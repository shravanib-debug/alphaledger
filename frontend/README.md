# AlphaLedger dashboard

React 19 + Vite 8 + TypeScript (strict) + Tailwind CSS 4, with Radix primitives and our own components.
Design system, screens and milestones: [docs/ui-plan.md](../docs/ui-plan.md).

```bash
npm install
npm run dev        # http://localhost:5173 (proxies /api to the gateway on :8080)
npm test           # Vitest + Testing Library
npm run lint       # oxlint
npm run build      # type check + production build into dist/
```

In development, `/style-guide` shows every design token and component.

| Path | Contents |
|---|---|
| `src/styles/index.css` | Design tokens (colour, type, radius) and typography roles |
| `src/lib/format.ts` | Indian number grouping, lakh/crore, P&L signs, IST dates |
| `src/components/ui/` | Design-system components: tables, figures, forms, tags, source badges, states |
| `src/features/` | One folder per screen (from M2) |
