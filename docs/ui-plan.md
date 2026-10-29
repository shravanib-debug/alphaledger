# AlphaLedger: UI Plan (Phase 8)

> The build plan for the React dashboard. It turns the Phase 8 checklist in [roadmap.md](roadmap.md) into a design
> system, an architecture, screen-by-screen specifications against the real API, and an ordered set of milestones.
> Visual direction: **editorial finance × institutional technology** (approved reference: the "Your portfolios, at a
> glance." overview mock-up). Conventions follow [conventions.md](conventions.md) §6.

**Definition of Done (roadmap):** a user can log in, create a trade, see market value change, upload a custodian
file and resolve a break, entirely from the UI.

---

## 1. Principles

1. **Truthful numbers.** Every figure on screen comes from an API response. No invented deltas, no placeholder
   series, no "Cash & Other" slice that the backend does not compute. A value that does not exist is shown as "—"
   with a reason, never as zero. Unpriced holdings say *unpriced*; stale prices say *stale* with their date; a
   negative NAV is shown as negative.
2. **Less decoration, better typography.** Hierarchy comes from type scale, weight, whitespace and hairline rules,
   not from colour, boxes or shadows.
3. **Burgundy is a signal.** It marks the active place, the primary action and focus. Green and red mean gain, loss or
   error, and nothing else.
4. **Dense where the work is.** Tables and forms are compact and aligned for daily professional use; headlines and
   summary figures get the generous space.
5. **The backend decides permissions.** Menus and buttons hide what a role cannot do, but every rule is enforced by
   the services; the UI never relies on hiding.

---

## 2. Technology

| Concern | Choice | Notes |
|---|---|---|
| Build | Vite 8, React 19, TypeScript 7 (`strict`, `noUncheckedIndexedAccess`) | Node 24 (installed in Phase 0); `npm` |
| Styling | Tailwind CSS with design tokens (§3) | No component theme to fight; every visual decision is ours |
| Primitives | Radix UI (dialog, dropdown menu, popover, tabs, tooltip, select, toast) | Accessible behaviour and keyboard support, no styling |
| Tables | TanStack Table 9 (`tableFeatures` + `useTable`) | Typed column meta for alignment, server pagination |
| Server state | TanStack Query | Caching, polling, loading and error states |
| HTTP | Axios, one module per backend service in `src/api/` | JWT and refresh interceptor (§5) |
| Routing | React Router 8 | Protected and role-checked routes |
| Forms | React Hook Form + Zod | Schemas mirror the backend validation |
| Charts | Recharts, restyled (§3.6) | Line/area, donut, stacked bars |
| Icons | Phosphor, 13–20 px, regular/light weight | Monochrome, never on coloured backgrounds |
| Fonts | Newsreader (serif), Inter (sans), self-hosted via `@fontsource` | Works offline, like the rest of the stack |
| Dates | `date-fns` with the `Asia/Kolkata` zone | Matches the backend's market clock |
| Tests | Vitest, React Testing Library, MSW, `@testing-library/user-event` | Mocked API; no backend needed |
| Lint | oxlint (react, typescript rules; the Vite template default) | Run in CI in Phase 9 |
| Serve | nginx (multi-stage Docker image) | Proxies `/api` to the gateway (§9) |

---

## 3. Design system

### 3.1 Colour tokens

| Token | Hex | Use |
|---|---|---|
| `ivory` | `#F8F4EF` | Page background |
| `ivory-raised` | `#FBF8F4` | Panels that need a frame (chart, donut), table header row |
| `espresso` | `#342F31` | Primary text, figures |
| `grey` | `#77716F` | Secondary text, labels, axis text |
| `taupe` | `#D8C9B8` | Subtle fills (selected row, tag background), donut segment |
| `border` | `#E5DDD6` | Hairline dividers, input borders, table rules |
| `burgundy` | `#692C3B` | Active nav, primary button, focus ring, links, chart line |
| `burgundy-tint` | `#F0E6E6` | Active nav background, hover on burgundy controls |
| `rose` | `#B88B82` | Secondary accent, second chart series |
| `gain` | `#3D6B4F` | Positive P&L, BUY |
| `loss` | `#9B3B3B` | Negative P&L, SELL, errors |
| `warn` | `#9A6B1F` on `#F4EAD6` | Stale price, warnings |

Green and red are muted so they read as information, not alarm. Contrast: `espresso` on `ivory` is 11.6:1, `grey` on
`ivory` 4.6:1 (AA for body text); `burgundy` on `ivory` 9.4:1.

### 3.2 Typography

| Role | Font | Size / line | Weight | Example |
|---|---|---|---|---|
| Page headline | Newsreader | 40/44 (32/36 on small screens) | 400 | "Your portfolios, at a glance." |
| Page description | Newsreader | 18/26 | 400, `grey` | "Real-time positions, valuation and…" |
| Section title | Newsreader | 22/28 | 400 | "Portfolio Value", "Recent Trades" |
| Headline figure | Newsreader | 30/34, tabular | 400 | "₹ 12.48 Cr" |
| Eyebrow / label | Inter | 11/16, uppercase, +0.08em tracking | 500, `grey` | "TOTAL AUM", "PORTFOLIO OVERVIEW" |
| Body / UI | Inter | 14/20 | 400 | Forms, menus |
| Table cell | Inter | 13/18, `tabular-nums` | 400 | Rows |
| Table header | Inter | 11/16, uppercase, +0.06em | 500, `grey` | "QUANTITY" |
| Metadata | Inter | 12/16 | 400, `grey` | "vs. previous snapshot", source dates |

Bold is reserved for one thing per area at most. Numbers always use `font-variant-numeric: tabular-nums`.

### 3.3 Space, shape, lines

- 4 px base grid; common steps 8, 12, 16, 24, 32, 48, 64.
- Content area max width 1440 px; 32 px page padding (16 px on phones).
- Sidebar 240 px; top bar 64 px.
- Radius: 2 px for inputs, buttons and tags; 4 px for panels and dialogs. Nothing is pill-shaped.
- Lines: 1 px `border`. Summary figures are separated by vertical hairlines, not cards.
- Shadows: none, except a single soft shadow on floating layers (menus, popovers, dialogs).
- Motion: 120 ms colour/opacity transitions on hover and focus. No entrance animations; charts render without
  animation.

### 3.4 Number and date formatting (`src/lib/format.ts`)

| Case | Rule | Example |
|---|---|---|
| Table amounts | Indian grouping, 2 decimals, currency in the column header | `24,56,500.00` under "VALUE (₹)" |
| Headline amounts | ₹ + lakh/crore, chosen by size | `₹ 3.86 L`, `₹ 12.48 Cr`; below ₹1 L shown in full |
| Prices | Up to 4 decimals, trailing zeros trimmed to 2 | `434.91`, `97.6025` |
| Quantities | Indian grouping, decimals only if present | `1,000`, `12.5` |
| Percentages | 2 decimals | `50.57%` |
| P&L | Sign, colour (`gain` / `loss`) and an arrow glyph for screen readers | `▲ 5,000.00` |
| Dates | `15 Oct 2026`; with weekday in the top bar (`15 Oct 2026 · Thu`) | |
| Timestamps | IST, `15 Oct 2026, 02:00 IST` | |
| Missing | `—` with a tooltip giving the reason (unpriced, no snapshot yet) | |

Amounts arrive as JSON numbers from `BigDecimal`; they are formatted, never recomputed, except for client-side sums
the API does not provide (§6.2), which use integer paise to avoid floating-point drift.

### 3.5 Components (`src/components/ui/`)

| Component | Notes |
|---|---|
| `PageHeader` | Eyebrow, serif headline, description, right-aligned slot (e.g. "As of" date picker) |
| `FigureRow` / `Figure` | Label, serif value, optional delta and comparison text; vertical hairline separators |
| `Section` | Serif title, optional actions ("View all trades →"), hairline below |
| `Panel` | The only framed surface: `ivory-raised`, 1 px border, 4 px radius |
| `DataTable` | TanStack Table wrapper: uppercase headers, numeric columns right-aligned, hairline rows, row hover in `taupe`/30%, sticky header, server pagination, empty and loading rows |
| `Tag` | Square-cornered status label; variants `neutral`, `outline`, `positive`, `negative`, `warn`, `accent` |
| `SourceBadge` | Price source (`NSE`, `AMFI`, `MFAPI`, `SYNTH L3`, `CSV`) + data date; amber when stale |
| `Button` | `primary` (burgundy), `secondary` (outline), `ghost`, `danger`; 32 px and 28 px heights |
| `Input`, `Select`, `DateInput`, `NumberInput`, `Textarea`, `FileDrop` | Hairline borders, burgundy focus ring, errors in `loss` below the field |
| `Dialog`, `ConfirmDialog` | Radix; used for cancel trade, resolve break, pay liability |
| `Menu`, `Tabs`, `Tooltip`, `Toast` | Radix-based |
| `StatusStrip` | Bottom-of-page links (open breaks, pending settlements, latest ETL run) |
| `EmptyState`, `ErrorState`, `Skeleton` | One sentence, one action; skeletons are hairline bars, not shimmer |
| `Money`, `Pnl`, `Pct`, `DateText` | Formatting components built on §3.4 |

A `/style-guide` route (development builds only) shows every token and component, and is reviewed before screens are
built (milestone M1).

### 3.6 Charts

- No chart borders, no vertical gridlines; horizontal gridlines in `border` at most every 4 ticks.
- Axis text 11 px `grey`; no axis lines.
- Single series: 1.5 px `burgundy` line over a `burgundy` area at 8% opacity (the reference's Portfolio Value chart).
- Categorical: `burgundy`, `rose`, `taupe`, `#8A7F78`, `#C9B8A8`, in that order, so neighbours stay distinguishable.
- Donut: 18 px ring, total in the centre in Newsreader, legend to the right with percentages right-aligned.
- Tooltips: `ivory` panel, hairline border, serif value, date below.
- Every chart has a text alternative (a visually hidden table) for screen readers.

---

## 4. Application shell and navigation

```
┌──────────────┬───────────────────────────────────────────────────────────────┐
│ ALPHALEDGER  │  [ Search instruments, trades, portfolios… ]   15 Oct 2026 · Thu │
│ ──────       │                                               PP Priya Patel ▾ │
│ ▌Overview    ├───────────────────────────────────────────────────────────────┤
│  Portfolios  │  EYEBROW                                                       │
│  Trades      │  Serif page headline.                          [As of ▾]       │
│  Positions   │  Description in serif grey.                                    │
│  Valuation   │  ───────────────────────────────────────────────────────────── │
│  Reconcil.   │  page content                                                  │
│  Analytics   │                                                                │
│              │                                                                │
│ ──────       │                                                                │
│  Notific. ③  │                                                                │
│  Administr.  │                                                                │
└──────────────┴───────────────────────────────────────────────────────────────┘
```

- **Sidebar:** wordmark in Newsreader with wide letter-spacing; nav items with 18 px line icons; the active item has a
  2 px burgundy left rule, `burgundy-tint` background and burgundy text. Notifications (with unread count) and
  Administration sit at the bottom. Collapses to icons below 1280 px and to a drawer below 768 px.
- **Top bar:** global search, today's date in IST (from the browser clock, shown in the market zone), user menu
  (name, role, sign out).
- **Global search:** one box, grouped results: instruments (`GET /instruments?isin=` or name filter), trades by
  reference (`GET /trades?…`), portfolios (from the cached portfolio list). Keyboard: `/` focuses, arrows move,
  Enter opens.
- **Portfolio context:** pages that need a portfolio (Positions, Valuation & NAV) show a portfolio selector in the
  page header; the choice is kept in the URL (`?portfolio=2`) so links are shareable.

### 4.1 Routes and roles

| Route | Screen | ADMIN | PM | OPS_ANALYST | AUDITOR |
|---|---|---|---|---|---|
| `/login` | Login | public | public | public | public |
| `/` | Overview | ✓ | ✓ | ✓ | ✓ |
| `/portfolios`, `/portfolios/:id` | Portfolios and funds | ✓ edit | ✓ edit own | read | read |
| `/trades` | Trade blotter | ✓ | ✓ | read | read |
| `/trades/new` | New trade (+ CSV upload) | ✓ | ✓ | — | — |
| `/trades/:id` | Trade detail and audit | ✓ cancel/settle | ✓ cancel | read | read |
| `/positions` | Positions | ✓ | ✓ | ✓ | ✓ |
| `/valuation` | Valuation & NAV (cash, liabilities, history, exposure) | ✓ edit | read | ✓ edit | read |
| `/recon` | Reconciliation runs and upload | ✓ | — | ✓ | read |
| `/recon/breaks`, `/recon/breaks/:id` | Breaks and break detail | ✓ | — | ✓ | read |
| `/analytics` | Analytics marts and data quality | ✓ | ✓ | ✓ | ✓ |
| `/notifications` | Notifications | ✓ | ✓ | ✓ | ✓ |
| `/admin/*` | Administration | ✓ | — | — | — |

The matrix mirrors the services' `@PreAuthorize` rules (`ReconController` allows ADMIN, OPS_ANALYST, AUDITOR;
trades write ADMIN, PM; cash and liabilities write ADMIN, OPS_ANALYST). It lives in one file (`src/auth/access.ts`) and
drives both the router guards and the menus. A route a role cannot open redirects to `/` with a quiet toast.

---

## 5. Architecture

### 5.1 Folders

```
frontend/
├── index.html
├── vite.config.ts            # dev proxy /api -> http://localhost:8080
├── tailwind.config.ts        # tokens from §3
├── Dockerfile, nginx.conf
└── src/
    ├── main.tsx, App.tsx, routes.tsx
    ├── api/                  # one module per backend service
    │   ├── http.ts           # Axios instance, interceptors, error mapping
    │   ├── auth.ts  portfolio.ts  trade.ts  position.ts  valuation.ts
    │   ├── marketdata.ts  recon.ts  notification.ts  analytics.ts  ops.ts
    │   └── types.ts          # response types, matching the Java records
    ├── auth/                 # AuthProvider, useAuth, access.ts, RequireRole
    ├── components/ui/        # design-system components (§3.5)
    ├── components/layout/    # Sidebar, TopBar, AppShell, GlobalSearch
    ├── features/
    │   ├── overview/  portfolios/  trades/  positions/  valuation/
    │   ├── recon/  notifications/  analytics/  admin/  login/
    │   └── <feature>/        # pages, components, hooks (useTrades.ts …), schemas
    ├── lib/                  # format.ts, dates.ts, money.ts (paise maths), errors.ts
    ├── styles/               # fonts, base CSS, Tailwind layers
    └── test/                 # MSW handlers, fixtures (from the demo data), render helpers
```

### 5.2 Authentication and the refresh flow

Implemented in M0; full contract in [auth-session.md](auth-session.md) §6.

- **Access token:** in memory only (React context), sent as `Authorization: Bearer`.
- **Refresh token:** never visible to the app. Login, refresh and logout send `X-Client: web`; the server keeps the
  refresh token in a `Secure; HttpOnly; SameSite=Strict` cookie scoped to `/api/v1/auth`.
- **Interceptor:** on a 401 from a business API, one refresh runs (single flight within the tab); concurrent requests
  wait for it and are retried once with the new access token. If refresh fails, the session is cleared and the user
  returns to `/login` with "Your session ended; please sign in again", keeping the page as `?next=`.
- **Across tabs:** every refresh runs inside `navigator.locks.request('alphaledger-refresh', …)`, so two tabs never
  present the same cookie at once. Each attempt carries a fresh `Idempotency-Key`; a network-error retry of the same
  attempt reuses it.
- **Proactive refresh:** 60 s before `expiresIn` runs out, so active users never see a 401.
- **Bootstrap:** on load, refresh first (restores the session from the cookie), then `GET /auth/me`.

### 5.3 Data fetching

- Query keys per resource (`['trades', filters]`, `['valuation', portfolioId, asOf]` …), defined next to each
  `api/` module.
- **Polling where the backend changes on its own:** valuation and positions every 15 s while the page is visible
  (prices and Kafka-driven position updates), notification count every 30 s, recon run status every 2 s while
  `RUNNING`.
- **After a write, invalidate what it affects:** creating a trade invalidates trades, then positions and valuation
  are polled until the event chain lands (the UI says "Updating positions…" rather than pretending it is instant).
- Retries: TanStack Query retries GETs once; the gateway already retries GETs on 5xx, so the client does not retry
  writes.

### 5.4 Errors

All services return `{ timestamp, status, error, message, path, correlationId }`. `lib/errors.ts` maps them:

| Status | UI |
|---|---|
| 400 `VALIDATION_ERROR` | Field errors when the message names a field (`quantity: must be > 0`), otherwise a form-level message |
| 401 | Refresh flow (§5.2) |
| 403 | "Your role does not allow this action" toast |
| 404 | Inline not-found state for detail pages |
| 409 / 422 | The service's message inline (e.g. "SELL exceeds holding", "Withdrawal exceeds the cash balance") |
| 429 | "Too many requests; retrying shortly", automatic retry after `Retry-After` for GETs |
| 503 | "Service temporarily unavailable" with the affected area named; page keeps the last data |
| Network | Offline banner at the top of the content area |

Error toasts show the `correlationId` in small grey text with a copy button, so a problem can be traced in the logs.

---

## 6. Screens

Each screen lists its purpose, layout, data sources, actions and states. Every screen has loading (skeleton),
empty (one sentence and one action) and error (message and retry) states.

### 6.1 Login (`/login`)
- Split layout: left half ivory with the wordmark, a serif line ("Portfolio management and reconciliation for Indian
  markets.") and a hairline; right half the form (email, password, "Sign in").
- `POST /auth/login`; errors inline ("Email or password is incorrect"). In development builds, the four demo users
  are listed as one-click fills.

### 6.2 Overview (`/`) — the reference screen
- **Header:** eyebrow "PORTFOLIO OVERVIEW", "Your portfolios, at a glance.", description, "As of" date (default
  today; past dates use `?asOf=`).
- **Figure row** (vertical hairlines): Total NAV · Market value · Cash · Liabilities · Unrealized P&L. Totals are
  the sum across the portfolios the user can see: `GET /portfolios`, then `GET /portfolios/{id}/valuation?asOf=` for
  each (3 demo portfolios; summed in paise). The delta under NAV compares with the previous end-of-day snapshot from
  `/valuation/history`; with no previous snapshot, no delta is shown.
- **Portfolio Value panel:** NAV over time from `/portfolios/{id}/valuation/history` (summed per day across
  portfolios). Range tabs 1M · 3M · 6M · 1Y · YTD are shown only when that much history exists; history starts with
  the first snapshot.
- **AUM by Asset Class panel:** donut from `/portfolios/{id}/exposure` (summed by asset class), centre total = market
  value. Cash is not a slice (exposure is a share of market value; NAV is shown in the figure row).
- **Recent Trades:** `GET /trades?size=5`, columns Date · Portfolio · ISIN · Instrument · Side · Quantity · Price (₹)
  · Value (₹) · Status, "View all trades →".
- **Status strips:** Open reconciliation breaks (`/recon/breaks?status=OPEN`, count; Ops/Admin/Auditor only) ·
  Pending settlements (`/trades?status=VALIDATED`, `totalElements`) · Latest ETL run (`/analytics/etl-runs`, first
  row, with the count of failed checks from `/analytics/dq-results`).
- PMs without recon access see "Limit breaches" (`/valuations/limit-breaches`) in place of open breaks.

### 6.3 Portfolios (`/portfolios`, `/portfolios/:id`)
- List: name, manager, base currency, funds, status; detail: funds by asset class, NAV figure row, links to positions
  and trades filtered to the portfolio.
- PM/Admin: create portfolio, add fund, archive (`POST /portfolios`, `/funds`, `/archive`); PMs only for portfolios
  they manage (the API returns 403 otherwise).

### 6.4 Trades (`/trades`, `/trades/new`, `/trades/:id`)
- **Blotter:** filters in a single hairline row above the table (portfolio, status, side, ISIN, from–to) bound to
  the URL; `GET /trades` with server pagination (`PageResponse`: `content`, `page`, `size`, `totalElements`).
  Columns: Trade date · Ref · Portfolio · Instrument (name + ISIN in grey) · Side (BUY in `gain`, SELL in `loss`) ·
  Quantity · Price · Notional · Settle date · Status tag. Rejected rows show the reason in a tooltip.
- **New trade:** portfolio (managed by the PM), instrument (searchable by name or ISIN, `GET /instruments`), side,
  quantity, price, trade date (default today, business days only, using `GET /marketdata/holidays`), settle date
  (computed T+1 preview, editable). Shows the latest price with its `SourceBadge` as a reference and the notional.
  Zod mirrors `CreateTradeRequest`. On success: toast with the reference, link to the trade, "Updating positions…"
  until `/positions` reflects it. A REJECTED trade is shown as a result, not an error, because the backend stores it.
- **CSV upload** (tab on the same page): `POST /trades/upload`, results table of accepted and rejected rows.
- **Detail:** fields, status timeline from `GET /trades/{id}/audit`, actions Cancel (PM/Admin, `POST /cancel` with
  comment) and Settle (Admin, `POST /settle`).

### 6.5 Positions (`/positions?portfolio=`)
- Portfolio selector; table from `GET /portfolios/{id}/valuation` (`details.lines`): Instrument · Asset class ·
  Quantity · Avg cost · Price + `SourceBadge` (source, data date, stale) · Market value · Cost · Unrealized · Realized
  · Weight. Unpriced lines show "unpriced" in `warn` and are excluded from totals, as the API does.
- Footer figures: priced / stale / unpriced counts from the response.
- Tabs: Snapshots (`/positions/snapshots`), Exceptions (`/positions/exceptions`: SELLs that could not be applied).

### 6.6 Valuation & NAV (`/valuation?portfolio=`)
- **NAV breakdown** as an editorial equation: Market value + Cash − Liabilities = **NAV**, serif figures, basis
  `FULL_NAV` noted in grey.
- **Exposure:** by asset class and by instrument (`/exposure`), with limit rules and breaches overlaid
  (`/valuations/limit-rules`, `/limit-breaches`).
- **History:** NAV and market value lines (`/valuation/history`).
- **Cash:** balance and ledger (`GET /cash`); Admin/Ops can deposit or withdraw (`POST /cash`), with the
  withdrawal-exceeds-balance message inline.
- **Liabilities:** open and paid (`GET /liabilities?status=`); Admin/Ops add, edit, pay, delete (borrowings cannot be
  deleted or resized: the buttons are absent and the API also refuses).

### 6.7 Reconciliation (`/recon`, `/recon/breaks`, `/recon/breaks/:id`)
- **Runs:** upload panel (`FileDrop`, run date, `POST /recon/runs` multipart) and run history (`GET /recon/runs`).
  A run summary shows totals, matched, match rate and the four break counts as a figure row; rejected rows from
  `/runs/{id}/errors`. Re-uploading the same file returns the same run, and the UI says so.
- **Breaks:** filters (status, type, run, assignee, minimum age) bound to the URL; columns Age · Type · Trade ref ·
  ISIN · Portfolio · Internal · Custodian · Difference · Status · Assignee; overdue (SLA) rows marked with a `warn`
  rule on the left edge.
- **Break detail:** two aligned columns, *Internal* and *Custodian*, with differing values highlighted in `loss`;
  actions Assign (to me, or to a chosen analyst), Escalate, Resolve (Accept external · Adjust internal · Known
  difference), each with a required comment (`POST /assign`, `/escalate`, `/resolve`); audit history from `/audit`
  as a timeline (who, when, from → to, comment).

### 6.8 Notifications (`/notifications` and the bell)
- Bell in the sidebar with unread count (`/notifications/unread-count`, polled). The popover lists the latest 8; the
  page lists all with an "Unread only" filter, mark read on open (`POST /{id}/read`), "Mark all read".
- Each notification links to its subject: a break opens break detail; a limit breach opens Valuation & NAV.

### 6.9 Analytics (`/analytics`)
- AUM by asset class (date picker; `/analytics/aum-by-asset-class`), daily P&L by portfolio (`/daily-pnl`), break
  trend as stacked bars of open breaks per type with raised/resolved lines (`/break-trend`), break ageing as a
  bucket table (`/break-ageing`).
- **Data quality:** latest run status and its checks grouped by category with PASSED/FAILED tags (`/dq-results`);
  ETL run history (`/etl-runs`).

### 6.10 Administration (`/admin`, Admin only)
- **Users:** list and create (`/auth/users`).
- **Jobs:** run end-of-day valuation (`POST /valuations/eod-runs`), fee accrual (`/valuations/fee-accruals`),
  analytics ETL with full-refresh option (`POST /analytics/etl-runs`), open-breaks digest
  (`POST /notifications/digests`). Each shows its last result.
- **Market data:** ingestion log and rejected rows (`/marketdata/ingestions`), CSV price upload, backfill.
- **Limit rules:** create, edit, delete (`/valuations/limit-rules`).
- **Dead letters:** topics, pending records with their error, replay with confirmation (`/ops/dead-letters`).

---

## 7. Accessibility and responsiveness

- WCAG 2.1 AA: contrast per §3.1, visible burgundy focus ring (2 px, offset 2 px), full keyboard use (Radix), labels
  on every input, `aria-live` for toasts and the "Updating positions…" message, chart text alternatives.
- Colour is never the only signal: P&L carries a sign and arrow, statuses carry text, stale prices carry the word.
- Breakpoints: ≥1280 full layout; 768–1279 icon sidebar, figure row wraps to two lines, chart and donut stack;
  <768 drawer navigation, tables scroll horizontally inside their section with the first column pinned.

---

## 8. Testing

| Level | What | Tools |
|---|---|---|
| Unit | Formatters (lakh/crore, Indian grouping, paise sums), access matrix, error mapping, T+1 preview | Vitest |
| Component | Each design-system component's variants and states | Vitest + RTL |
| Flow | Login and refresh (including concurrent 401s and a failed refresh); create trade (valid, rejected, 422); blotter filters and paging; recon upload and run summary; break assign → resolve with comment; cash withdrawal over balance; role guards per role | RTL + user-event + MSW (fixtures from the real demo data: portfolios, ISINs, seed prices) |
| End to end (Phase 9) | The roadmap DoD path against the Docker stack | Playwright, run in CI |

Target: every flow in the DoD covered at the flow level before M7 closes.

---

## 9. Delivery

- **Dockerfile:** multi-stage, `node:24-alpine` builds, `nginx:1.27-alpine` serves `dist/`.
- **nginx.conf:** SPA fallback to `index.html`; `/api/` proxied to `api-gateway:8080` (same origin, so no CORS in
  the container setup); long-cache hashed assets, no-cache `index.html`; security headers: Content-Security-Policy
  (`default-src 'self'`; fonts and scripts self-hosted), `X-Content-Type-Options`, `Referrer-Policy`,
  `X-Frame-Options: DENY`.
- **docker-compose:** `frontend` service on port 3000 (the gateway's configured CORS origin, `FRONTEND_ORIGIN`, for
  `npm run dev`).
- **Development:** `npm run dev` on :5173 with Vite proxying `/api` to `localhost:8080`; the stack runs as today.
- **README:** a Dashboard section with screenshots once M7 closes.

---

## 10. Backend prerequisites and follow-ups

Done before or alongside the screens that need them (small, separate commits):

| # | Change | Needed by | Why |
|---|---|---|---|
| B0 | Refresh token in an HttpOnly cookie for browsers, row-locked rotation, key-bound replay — **done** ([auth-session.md](auth-session.md)) | Login, session restore | Script on the page must never read the refresh token |
| B1 | **Done:** dates as `yyyy-MM-dd` everywhere a service returns rows as maps (valuation history, cash, liabilities, recon runs and breaks, ledger, limit breaches) | All tables | Today they serialise as `2026-10-15T00:00:00.000Z`; fixed once in `common` (`SqlDateJsonModule`) for every service |
| B2 | **Done:** `GET /api/v1/auth/users/directory` for any signed-in user: `id`, `fullName`, `role` (no emails, no status) | Break assignment, audit timelines, assignee column | `/auth/users` is Admin-only, so Ops cannot pick an assignee or see names |
| B3 | Check every list endpoint used by the UI returns what the screen needs without N+1 calls; instrument names come from one cached `GET /instruments` (29 instruments) | Blotter, positions | Avoids a call per row |

Follow-ups, not in Phase 8:
- Server-side search endpoint if the instrument or trade lists outgrow client-side search.
- Live updates over Server-Sent Events instead of polling.

---

## 11. Milestones

Each milestone ends with its tests green, the roadmap ticked and a commit on `develop` (`feat(frontend): …`).

| # | Milestone | Contents | Roadmap items |
|---|---|---|---|
| M0 | Backend prerequisites — **done** | B0, B1, B2 with tests | — |
| M1 | Foundation and style guide (built; awaiting review) | Vite + TS scaffold, Tailwind tokens, fonts, `lib/format`, every §3.5 component, `/style-guide` page — **review point** | Scaffold; UI library and theme |
| M2 | Shell and auth | Login, AuthProvider, Axios interceptors and refresh, access matrix, route guards, sidebar, top bar, global search, notification bell | Axios client with JWT; routing and role menus; login |
| M3 | Overview, positions, valuation | §6.2, §6.5, §6.6 (cash and liabilities included) | Portfolio dashboard; positions with source badge |
| M4 | Trades | Blotter, new trade, CSV upload, detail and audit, cancel/settle | Blotter; create-trade form |
| M5 | Reconciliation | Upload, runs, breaks, break detail and workflow | Reconciliation page; break detail page |
| M6 | Notifications, analytics, administration, portfolios | §6.3, §6.8–§6.10 | Notifications; analytics page |
| M7 | Quality and delivery | State coverage audit, accessibility pass, responsive pass, flow tests, Dockerfile, nginx, compose, smoke test addition (frontend serves and proxies `/api`), README section | Loading/empty/error; responsive; component tests; Dockerfile + nginx |

**DoD check (end of M7):** on the Docker stack, as Priya create a trade and watch Positions and the Overview update;
as Rohan upload `custodian-<date>.csv`, open a quantity break, assign it to himself and resolve it with a comment; as
Vikram confirm every action is read-only.

---

## 12. Decisions

| Decision | Choice | Alternatives considered |
|---|---|---|
| Component approach | Tailwind + Radix, own components | Mantine, MUI: their defaults (rounded, filled, shadowed) conflict with the visual direction |
| Token storage | Access in memory; refresh in an HttpOnly cookie set by auth-service | `sessionStorage` / `localStorage`: readable by any script on the page |
| Overview totals | Summed in the browser from per-portfolio valuations | New aggregate endpoint: unnecessary for 3 portfolios; revisit with many |
| Live data | Polling while visible | WebSocket/SSE: more moving parts for little gain at this scale |
| Fonts | Self-hosted Newsreader and Inter | Google Fonts CDN: breaks the offline stack and the CSP |
