import { ArrowRight, Bank, ClockCountdown, Database } from '@phosphor-icons/react'
import { useState } from 'react'
import { Area, AreaChart, CartesianGrid, Cell, Pie, PieChart, ResponsiveContainer, Tooltip as ChartTooltip, XAxis, YAxis } from 'recharts'

import { Button } from '../../components/ui/Button'
import { DataTable } from '../../components/ui/DataTable'
import { columnsFor } from '../../components/ui/tableColumns'
import { Dialog } from '../../components/ui/Dialog'
import { Field, Input, NumberInput, Textarea } from '../../components/ui/Field'
import { Figure, FigureRow, PageHeader, Panel, Section } from '../../components/ui/Layout'
import { Select } from '../../components/ui/Select'
import { SourceBadge } from '../../components/ui/SourceBadge'
import { EmptyState, ErrorState, SkeletonRows } from '../../components/ui/States'
import { StatusStrip } from '../../components/ui/StatusStrip'
import { TabPanel, Tabs } from '../../components/ui/Tabs'
import { StatusTag, Tag } from '../../components/ui/Tag'
import { DateText, Missing, Money, Pct, Pnl, Price, Quantity } from '../../components/ui/Values'
import { compactRupees } from '../../lib/format'

/*
 * Development-only reference of every token and component (docs/ui-plan.md §3.5, milestone M1 review point).
 * Sample rows use the demo instruments and their 14/15-Oct-2026 closes; the NAV series is illustrative, labelled.
 */

const COLOURS: { token: string; hex: string; use: string }[] = [
  { token: 'ivory', hex: '#F8F4EF', use: 'Page background' },
  { token: 'ivory-raised', hex: '#FBF8F4', use: 'Framed panels, table header' },
  { token: 'espresso', hex: '#342F31', use: 'Primary text and figures' },
  { token: 'grey', hex: '#77716F', use: 'Labels, metadata' },
  { token: 'taupe', hex: '#D8C9B8', use: 'Subtle fills, tags' },
  { token: 'border', hex: '#E5DDD6', use: 'Hairlines' },
  { token: 'burgundy', hex: '#692C3B', use: 'Active place, primary action, focus' },
  { token: 'rose', hex: '#B88B82', use: 'Second series' },
  { token: 'gain', hex: '#3D6B4F', use: 'Positive P&L only' },
  { token: 'loss', hex: '#9B3B3B', use: 'Negative P&L, errors only' },
  { token: 'warn', hex: '#9A6B1F', use: 'Stale prices, warnings' },
]

interface PositionRow {
  instrument: string
  isin: string
  assetClass: string
  quantity: number
  price: number | null
  source: string | null
  dataDate: string | null
  stale: boolean
  marketValue: number | null
  unrealized: number | null
}

const POSITIONS: PositionRow[] = [
  { instrument: 'Embassy Office Parks REIT', isin: 'INE041025011', assetClass: 'Real estate', quantity: 500, price: 440, source: 'NSE_BHAVCOPY', dataDate: '2026-10-15', stale: false, marketValue: 220000, unrealized: 5000 },
  { instrument: 'Reliance Industries', isin: 'INE002A01018', assetClass: 'Private equity', quantity: 100, price: 1174.6, source: 'NSE_BHAVCOPY', dataDate: '2026-10-14', stale: true, marketValue: 117460, unrealized: -2540 },
  { instrument: 'PFC 7.4% 2034', isin: 'INE134E08MQ2', assetClass: 'Credit', quantity: 1000, price: 97.6, source: 'NSE_BHAVCOPY', dataDate: '2026-10-14', stale: true, marketValue: 97600, unrealized: -400 },
  { instrument: 'Northwind Agritech (synthetic)', isin: 'ZZSYN0000012', assetClass: 'Private equity', quantity: 250, price: null, source: null, dataDate: null, stale: false, marketValue: null, unrealized: null },
]

const col = columnsFor<PositionRow>()
const COLUMNS = [
  col.accessor('instrument', {
    header: 'Instrument',
    cell: (c) => (
      <span>
        <span className="block">{c.getValue()}</span>
        <span className="type-meta tabular">{c.row.original.isin}</span>
      </span>
    ),
  }),
  col.accessor('assetClass', { header: 'Asset class', meta: { muted: true } }),
  col.accessor('quantity', { header: 'Quantity', meta: { align: 'right' }, cell: (c) => <Quantity value={c.getValue()} /> }),
  col.accessor('price', {
    header: 'Price (₹)',
    meta: { align: 'right' },
    cell: (c) => {
      const r = c.row.original
      return r.price === null ? (
        <span className="text-warn">unpriced</span>
      ) : (
        <span className="inline-flex items-center justify-end gap-2">
          <Price value={r.price} />
          {r.source && r.dataDate && <SourceBadge source={r.source} dataDate={r.dataDate} stale={r.stale} />}
        </span>
      )
    },
  }),
  col.accessor('marketValue', {
    header: 'Market value (₹)',
    meta: { align: 'right' },
    cell: (c) => <Money value={c.getValue()} reason="Unpriced: no price on or before this date" />,
  }),
  col.accessor('unrealized', {
    header: 'Unrealized (₹)',
    meta: { align: 'right' },
    cell: (c) => <Pnl value={c.getValue()} reason="Unpriced" />,
  }),
]

// Illustrative series (sample): the shape of a NAV history line, not real data.
const NAV_SERIES = [
  { date: '1 Oct', nav: 41.2 }, { date: '3 Oct', nav: 41.9 }, { date: '7 Oct', nav: 41.5 }, { date: '9 Oct', nav: 42.6 },
  { date: '13 Oct', nav: 42.1 }, { date: '14 Oct', nav: 43.4 }, { date: '15 Oct', nav: 43.5 },
]

const ALLOCATION = [
  { name: 'Real estate', value: 220000, colour: 'var(--color-burgundy)' },
  { name: 'Private equity', value: 117460, colour: 'var(--color-rose)' },
  { name: 'Credit', value: 97600, colour: 'var(--color-taupe)' },
]
const ALLOCATION_TOTAL = ALLOCATION.reduce((s, a) => s + a.value, 0)

export function StyleGuidePage() {
  const [tab, setTab] = useState('components')
  const [dialogOpen, setDialogOpen] = useState(false)
  const [side, setSide] = useState<string>('BUY')
  const [tableState, setTableState] = useState('data')

  return (
    <main className="mx-auto max-w-[1440px] px-4 py-10 md:px-8">
      <PageHeader
        eyebrow="Design system"
        title="AlphaLedger style guide."
        description="Tokens and components for the dashboard. Sample rows use the demo instruments and their October closes."
        aside={<Tag tone="outline">M1 review</Tag>}
      />

      <Tabs
        value={tab}
        onValueChange={setTab}
        tabs={[{ value: 'components', label: 'Components' }, { value: 'foundations', label: 'Foundations' }]}
      >
        <TabPanel value="foundations">
          <Section title="Colour" className="mt-4">
            <div className="grid grid-cols-1 gap-x-10 sm:grid-cols-2 lg:grid-cols-3">
              {COLOURS.map((c) => (
                <div key={c.token} className="flex items-center gap-4 border-b border-border py-3">
                  <span className="h-9 w-9 shrink-0 rounded-[var(--radius-control)] border border-border" style={{ background: c.hex }} />
                  <span className="min-w-0">
                    <span className="block text-[13px] font-medium">{c.token}</span>
                    <span className="type-meta tabular">{c.hex} &nbsp; {c.use}</span>
                  </span>
                </div>
              ))}
            </div>
          </Section>

          <Section title="Typography">
            <div className="divide-y divide-border">
              <TypeRow role="Page headline · Newsreader 40/44">
                <span className="type-headline">Your portfolios, at a glance.</span>
              </TypeRow>
              <TypeRow role="Description · Newsreader 18/26">
                <span className="type-description">Positions, valuation and operational status across your funds.</span>
              </TypeRow>
              <TypeRow role="Section title · Newsreader 22/28">
                <span className="type-section">Recent trades</span>
              </TypeRow>
              <TypeRow role="Headline figure · Newsreader 30/34">
                <span className="type-figure">{compactRupees(124_860_000)}</span>
              </TypeRow>
              <TypeRow role="Label · Inter 11/16 uppercase">
                <span className="type-label">Total NAV</span>
              </TypeRow>
              <TypeRow role="Body · Inter 14/20">
                <span>Cash moves when a trade settles; paying a liability leaves NAV unchanged.</span>
              </TypeRow>
              <TypeRow role="Table cell · Inter 13/18 tabular">
                <span className="tabular text-[13px]">24,56,500.00 &nbsp; 1,174.60 &nbsp; 1,000</span>
              </TypeRow>
              <TypeRow role="Metadata · Inter 12/16">
                <span className="type-meta">vs. the previous end-of-day snapshot</span>
              </TypeRow>
            </div>
          </Section>

          <Section title="Numbers">
            <dl className="grid grid-cols-1 gap-x-10 sm:grid-cols-2">
              <ValueRow label="Table amount"><Money value={2456500} /></ValueRow>
              <ValueRow label="Headline amount"><Money value={386000} compact /></ValueRow>
              <ValueRow label="Price"><Price value={97.6025} /></ValueRow>
              <ValueRow label="Quantity"><Quantity value={150000} /></ValueRow>
              <ValueRow label="Gain"><Pnl value={5000} /></ValueRow>
              <ValueRow label="Loss"><Pnl value={-2540} /></ValueRow>
              <ValueRow label="Negative NAV"><Money value={-12540} compact /></ValueRow>
              <ValueRow label="Percentage"><Pct value={50.567} /></ValueRow>
              <ValueRow label="Date"><DateText value="2026-10-15" /></ValueRow>
              <ValueRow label="Missing value"><Missing reason="No end-of-day snapshot for this date yet" /></ValueRow>
            </dl>
          </Section>
        </TabPanel>

        <TabPanel value="components">
          <FigureRow>
            <Figure label="Total NAV" value={compactRupees(43_550_000)} detail={<Pnl value={11_400} />} />
            <Figure label="Market value" value={compactRupees(43_506_000)} />
            <Figure label="Cash" value={compactRupees(85_000)} />
            <Figure label="Liabilities" value={compactRupees(2_505.96)} />
            <Figure label="Unrealized P&L" value={<Pnl value={2060} />} />
          </FigureRow>

          <div className="mt-2 grid grid-cols-1 gap-6 lg:grid-cols-[1.7fr_1fr]">
            <Panel>
              <div className="mb-4 flex items-baseline justify-between">
                <h2 className="type-section">Portfolio value</h2>
                <span className="type-meta">Illustrative series</span>
              </div>
              <div className="h-56">
                <ResponsiveContainer>
                  <AreaChart data={NAV_SERIES} margin={{ top: 4, right: 4, bottom: 0, left: -12 }}>
                    <CartesianGrid vertical={false} stroke="var(--color-border)" />
                    <XAxis dataKey="date" axisLine={false} tickLine={false} tick={{ fill: 'var(--color-grey)', fontSize: 11 }} />
                    <YAxis axisLine={false} tickLine={false} tick={{ fill: 'var(--color-grey)', fontSize: 11 }} domain={['dataMin - 1', 'dataMax + 1']} unit=" L" />
                    <ChartTooltip
                      cursor={{ stroke: 'var(--color-taupe)' }}
                      contentStyle={{ background: 'var(--color-ivory)', border: '1px solid var(--color-border)', borderRadius: 2, fontSize: 12 }}
                      formatter={(v) => [`₹ ${v} L`, 'NAV']}
                    />
                    <Area type="monotone" dataKey="nav" stroke="var(--color-burgundy)" strokeWidth={1.5} fill="var(--color-burgundy)" fillOpacity={0.08} isAnimationActive={false} />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            </Panel>
            <Panel>
              <h2 className="type-section mb-4">AUM by asset class</h2>
              <div className="flex items-center gap-6">
                <div className="relative h-40 w-40 shrink-0">
                  <ResponsiveContainer>
                    <PieChart>
                      <Pie data={ALLOCATION} dataKey="value" innerRadius={52} outerRadius={70} stroke="none" isAnimationActive={false}>
                        {ALLOCATION.map((a) => <Cell key={a.name} fill={a.colour} />)}
                      </Pie>
                    </PieChart>
                  </ResponsiveContainer>
                  <span className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                    <span className="font-serif text-[17px] tabular">{compactRupees(ALLOCATION_TOTAL)}</span>
                    <span className="type-meta">Total</span>
                  </span>
                </div>
                <ul className="flex-1 space-y-2.5 text-[13px]">
                  {ALLOCATION.map((a) => (
                    <li key={a.name} className="flex items-center gap-2.5">
                      <span className="h-2.5 w-2.5 shrink-0 rounded-[1px]" style={{ background: a.colour }} aria-hidden />
                      <span className="flex-1">{a.name}</span>
                      <Pct value={(a.value / ALLOCATION_TOTAL) * 100} />
                    </li>
                  ))}
                </ul>
              </div>
            </Panel>
          </div>

          <Section
            title="Positions"
            actions={
              <div className="flex gap-1">
                {['data', 'loading', 'empty', 'error'].map((s) => (
                  <Button key={s} size="sm" variant={tableState === s ? 'secondary' : 'ghost'} onClick={() => setTableState(s)}>
                    {s}
                  </Button>
                ))}
              </div>
            }
          >
            {tableState === 'loading' ? (
              <SkeletonRows columns={6} rows={4} />
            ) : tableState === 'empty' ? (
              <EmptyState message="No positions in this portfolio yet." action={<Button size="sm">New trade</Button>} />
            ) : tableState === 'error' ? (
              <ErrorState message="Valuation service is temporarily unavailable; try again shortly." correlationId="3f2c9a1e-7b44" onRetry={() => setTableState('data')} />
            ) : (
              <DataTable columns={COLUMNS} data={POSITIONS} empty="No positions." caption="Sample positions" />
            )}
          </Section>

          <div className="mt-10 grid grid-cols-1 gap-x-12 gap-y-10 lg:grid-cols-2">
            <section>
              <h2 className="type-section mb-4 border-b border-border pb-2.5">Buttons and tags</h2>
              <div className="flex flex-wrap items-center gap-2">
                <Button variant="primary">Book trade</Button>
                <Button>Export</Button>
                <Button variant="ghost">Clear filters</Button>
                <Button variant="danger">Cancel trade</Button>
                <Button variant="primary" disabled>Saving</Button>
                <Button size="sm">Small</Button>
              </div>
              <div className="mt-5 flex flex-wrap items-center gap-2">
                {['VALIDATED', 'SETTLED', 'REJECTED', 'CANCELLED', 'OPEN', 'IN_REVIEW', 'ESCALATED', 'RESOLVED', 'PASSED', 'FAILED'].map((s) => (
                  <StatusTag key={s} status={s} />
                ))}
              </div>
              <div className="mt-5 flex flex-wrap items-center gap-2">
                <SourceBadge source="NSE_BHAVCOPY" dataDate="2026-10-15" stale={false} />
                <SourceBadge source="AMFI_NAV" dataDate="2026-10-16" stale={false} />
                <SourceBadge source="NSE_BHAVCOPY" dataDate="2026-10-14" stale />
                <SourceBadge source="SYNTHETIC" dataDate="2026-09-30" stale={false} fairValueLevel="L3" />
              </div>
              <div className="mt-6">
                <Button onClick={() => setDialogOpen(true)}>Open dialog</Button>
                <Dialog
                  open={dialogOpen}
                  onOpenChange={setDialogOpen}
                  title="Resolve break"
                  description="Quantity mismatch on TR-1042. A comment is required."
                  actions={
                    <>
                      <Button variant="ghost" onClick={() => setDialogOpen(false)}>Cancel</Button>
                      <Button variant="primary" onClick={() => setDialogOpen(false)}>Resolve</Button>
                    </>
                  }
                >
                  <Field label="Comment" helper="Recorded in the break's audit trail.">
                    {(a) => <Textarea {...a} placeholder="Custodian confirmed 90 units; booking corrected." />}
                  </Field>
                </Dialog>
              </div>
            </section>

            <section>
              <h2 className="type-section mb-4 border-b border-border pb-2.5">Form controls</h2>
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <Field label="ISIN" helper="12 characters, checked against the instrument master.">
                  {(a) => <Input {...a} defaultValue="INE041025011" className="tabular" />}
                </Field>
                <Field label="Side">
                  {(a) => <Select {...a} value={side} onValueChange={setSide} options={[{ value: 'BUY', label: 'Buy' }, { value: 'SELL', label: 'Sell' }]} />}
                </Field>
                <Field label="Quantity" error="SELL exceeds the current holding of 500.">
                  {(a) => <NumberInput {...a} defaultValue="750" />}
                </Field>
                <Field label="Price (₹)" helper="Latest close 434.91 (NSE, 14 Oct 2026).">
                  {(a) => <NumberInput {...a} defaultValue="435.00" />}
                </Field>
              </div>
            </section>
          </div>

          <div className="mt-12 grid grid-cols-1 md:grid-cols-3">
            <StatusStrip icon={Bank} label="Open reconciliation breaks" value="14" detail="3 raised since the last run" href="#" />
            <StatusStrip icon={ClockCountdown} label="Pending settlements" value="8" detail="Trades awaiting T+1 settlement" href="#" />
            <StatusStrip icon={Database} label="Latest ETL run" value={<span className="text-[18px] text-espresso">15 Oct 2026, 02:00 IST</span>} detail={<StatusTag status="COMPLETED" />} href="#" />
          </div>

          <p className="mt-10 flex items-center gap-1.5 text-[13px] text-burgundy">
            Links use burgundy text <ArrowRight size={13} />
          </p>
        </TabPanel>
      </Tabs>
    </main>
  )
}

function TypeRow({ role, children }: { role: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-1 items-baseline gap-2 py-4 md:grid-cols-[260px_1fr]">
      <span className="type-meta">{role}</span>
      <span className="min-w-0">{children}</span>
    </div>
  )
}

function ValueRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between border-b border-border py-3">
      <dt className="type-meta">{label}</dt>
      <dd className="text-[13px]">{children}</dd>
    </div>
  )
}
