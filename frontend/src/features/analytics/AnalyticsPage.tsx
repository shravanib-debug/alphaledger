import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { analyticsApi } from '../../api/analytics'
import type { BreakType } from '../../api/types'
import { Donut } from '../../components/ui/Charts'
import { ASSET_CLASS_LABELS, SERIES_COLOURS } from '../../components/ui/chartTheme'
import { PageHeader, Panel, Section } from '../../components/ui/Layout'
import { EmptyState, Skeleton } from '../../components/ui/States'
import { TabPanel, Tabs } from '../../components/ui/Tabs'
import { StatusTag, Tag } from '../../components/ui/Tag'
import { DateText, Money, Pct, Pnl } from '../../components/ui/Values'
import { usePortfolios } from '../../hooks/reference'
import { calendarDate, marketToday, timestamp } from '../../lib/format'
import { BREAK_TYPE_LABELS } from '../recon/breakLabels'

const TYPES: BreakType[] = ['MISSING_INTERNAL', 'MISSING_EXTERNAL', 'QTY_MISMATCH', 'PRICE_MISMATCH']

/** Warehouse marts and data quality (docs/ui-plan.md §6.9). Data is as of the latest nightly ETL run. */
export function AnalyticsPage() {
  const [tab, setTab] = useState('aum')
  const etl = useQuery({ queryKey: ['etl-runs'], queryFn: analyticsApi.etlRuns })
  const latest = etl.data?.[0]
  return (
    <>
      <PageHeader
        eyebrow="Analytics"
        title="The warehouse view."
        description="Loaded nightly at 02:00 IST from every service, with data-quality checks on each load."
        aside={latest ? <span className="type-meta flex items-center gap-2">Last load {timestamp(latest.started_at)} <StatusTag status={latest.status} /></span> : undefined}
      />
      {etl.data && etl.data.length === 0 ? (
        <EmptyState message="The warehouse has not been loaded yet. It loads nightly, or an administrator can run it now from Administration." />
      ) : (
        <div className="mt-6">
          <Tabs
            value={tab}
            onValueChange={setTab}
            tabs={[{ value: 'aum', label: 'AUM' }, { value: 'pnl', label: 'Daily P&L' }, { value: 'breaks', label: 'Breaks' }, { value: 'dq', label: 'Data quality' }]}
          >
            <TabPanel value="aum"><Aum /></TabPanel>
            <TabPanel value="pnl"><DailyPnlTab /></TabPanel>
            <TabPanel value="breaks"><Breaks /></TabPanel>
            <TabPanel value="dq"><DataQuality /></TabPanel>
          </Tabs>
        </div>
      )}
    </>
  )
}

function Aum() {
  const [date, setDate] = useState<string>()
  const aum = useQuery({ queryKey: ['aum', date], queryFn: () => analyticsApi.aum(date) })
  const rows = aum.data?.rows ?? []
  return (
    <div className="grid grid-cols-1 gap-8 xl:grid-cols-[1fr_1.2fr]">
      <Panel>
        <div className="mb-4 flex items-baseline justify-between gap-3">
          <h2 className="type-section">AUM by asset class</h2>
          <input type="date" value={date ?? aum.data?.date ?? ''} max={marketToday()} onChange={(e) => setDate(e.target.value || undefined)} className="h-8 rounded-[var(--radius-control)] border border-border bg-ivory-raised px-2 text-[13px] tabular" aria-label="Date" />
        </div>
        {aum.isLoading ? <Skeleton className="h-44 w-full" /> : rows.length === 0 ? <p className="type-meta py-10">No positions loaded for this date.</p> : (
          <Donut slices={rows.map((r) => ({ label: ASSET_CLASS_LABELS[r.asset_class] ?? r.asset_class, value: r.market_value }))} />
        )}
      </Panel>
      <section>
        <h2 className="type-section mb-3 border-b border-border pb-2.5">{aum.data?.date ? <>As of <DateText value={aum.data.date} /></> : 'Breakdown'}</h2>
        <table className="w-full text-[13px]">
          <thead>
            <tr className="border-b border-border">
              <th className="type-label py-2 text-left">Asset class</th>
              <th className="type-label py-2 text-right">Positions</th>
              <th className="type-label py-2 text-right">Market value (₹)</th>
              <th className="type-label py-2 text-right">Share</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.asset_class} className="border-b border-border">
                <td className="py-2.5">{ASSET_CLASS_LABELS[r.asset_class] ?? r.asset_class}</td>
                <td className="py-2.5 text-right tabular">{r.positions}</td>
                <td className="py-2.5 text-right"><Money value={r.market_value} /></td>
                <td className="py-2.5 text-right"><Pct value={r.pct_of_total} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  )
}

function DailyPnlTab() {
  const portfolios = usePortfolios()
  const [portfolioId, setPortfolioId] = useState<string>('')
  const pnl = useQuery({
    queryKey: ['daily-pnl', portfolioId],
    queryFn: () => analyticsApi.dailyPnl({ portfolioId: portfolioId ? Number(portfolioId) : undefined, from: '2020-01-01', to: marketToday() }),
  })
  const rows = [...(pnl.data ?? [])].reverse()
  return (
    <>
      <label className="mb-4 flex w-fit flex-col gap-1">
        <span className="type-meta">Portfolio</span>
        <select value={portfolioId} onChange={(e) => setPortfolioId(e.target.value)} className="h-8 min-w-[260px] rounded-[var(--radius-control)] border border-border bg-ivory-raised px-2 text-[13px]">
          <option value="">All portfolios</option>
          {(portfolios.data ?? []).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
      </label>
      {rows.length === 0 ? (
        <EmptyState message={pnl.isLoading ? 'Loading' : 'No end-of-day valuations in the warehouse yet.'} />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-[13px]">
            <thead>
              <tr className="border-b border-border">
                {['Date', 'Portfolio', 'Market value (₹)', 'NAV (₹)', 'Realized (₹)', 'Unrealized (₹)', 'Total P&L (₹)', 'Day (₹)'].map((h, i) => (
                  <th key={h} className={`type-label whitespace-nowrap px-3 py-2.5 first:pl-0 ${i > 1 ? 'text-right' : 'text-left'}`}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={`${r.date}-${r.portfolio_id}`} className="border-b border-border">
                  <td className="px-3 py-2.5 pl-0"><DateText value={r.date} /></td>
                  <td className="px-3 py-2.5">{r.portfolio_name}</td>
                  <td className="px-3 py-2.5 text-right"><Money value={r.market_value} /></td>
                  <td className="px-3 py-2.5 text-right"><Money value={r.nav} reason="No NAV on this snapshot" /></td>
                  <td className="px-3 py-2.5 text-right"><Pnl value={r.realized_pnl} /></td>
                  <td className="px-3 py-2.5 text-right"><Pnl value={r.unrealized_pnl} /></td>
                  <td className="px-3 py-2.5 text-right"><Pnl value={r.total_pnl} /></td>
                  <td className="px-3 py-2.5 text-right"><Pnl value={r.daily_pnl} reason="First snapshot: no previous day" /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function Breaks() {
  const trend = useQuery({ queryKey: ['break-trend'], queryFn: () => analyticsApi.breakTrend('2020-01-01', marketToday()) })
  const ageing = useQuery({ queryKey: ['break-ageing'], queryFn: analyticsApi.breakAgeing })
  const etl = useQuery({ queryKey: ['etl-runs'], queryFn: analyticsApi.etlRuns })
  const lastLoaded = etl.data?.find((r) => r.status === 'COMPLETED')?.run_date
  const series = useMemo(() => {
    const byDate = new Map<string, Record<string, number | string>>()
    for (const t of trend.data ?? []) {
      const row = byDate.get(t.date) ?? { date: t.date }
      row[t.break_type] = t.open_at_end_of_day
      byDate.set(t.date, row)
    }
    const rows = [...byDate.values()].sort((a, b) => String(a.date).localeCompare(String(b.date)))
    // The view covers days with activity only; with nothing raised or resolved since, the open counts are unchanged
    // up to the latest load, so the last day carries forward (one bar per day).
    const last = rows.at(-1)
    if (last && lastLoaded && lastLoaded > String(last.date)) {
      for (let d = nextDay(String(last.date)); d <= lastLoaded; d = nextDay(d)) {
        rows.push({ ...last, date: d })
      }
    }
    return rows
  }, [trend.data, lastLoaded])
  const buckets = [...new Set((ageing.data ?? []).map((a) => a.age_bucket))]

  return (
    <div className="grid grid-cols-1 gap-8 xl:grid-cols-[1.5fr_1fr]">
      <Panel>
        <h2 className="type-section mb-4">Open breaks at the end of each day</h2>
        {series.length === 0 ? <p className="type-meta py-10">No breaks loaded yet.</p> : (
          <div className="h-64">
            <ResponsiveContainer>
              <BarChart data={series} margin={{ top: 4, right: 4, bottom: 0, left: -16 }}>
                <CartesianGrid vertical={false} stroke="var(--color-border)" />
                <XAxis dataKey="date" axisLine={false} tickLine={false} tick={{ fill: 'var(--color-grey)', fontSize: 11 }} tickFormatter={(d: string) => calendarDate(d).slice(0, -5)} />
                <YAxis axisLine={false} tickLine={false} allowDecimals={false} tick={{ fill: 'var(--color-grey)', fontSize: 11 }} />
                <Tooltip contentStyle={{ background: 'var(--color-ivory)', border: '1px solid var(--color-border)', borderRadius: 2, fontSize: 12 }} labelFormatter={(d) => calendarDate(String(d))} />
                {TYPES.map((t, i) => (
                  <Bar key={t} dataKey={t} name={BREAK_TYPE_LABELS[t]} stackId="open" fill={SERIES_COLOURS[i]} isAnimationActive={false} />
                ))}
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
        {series.length > 0 && (
          <ul className="mt-3 flex flex-wrap justify-center gap-x-5 gap-y-1.5 text-[12px] text-espresso">
            {TYPES.map((t, i) => (
              <li key={t} className="flex items-center gap-1.5">
                <span className="h-2.5 w-2.5 rounded-[1px]" style={{ background: SERIES_COLOURS[i] }} aria-hidden />
                {BREAK_TYPE_LABELS[t]}
              </li>
            ))}
          </ul>
        )}
      </Panel>
      <section>
        <h2 className="type-section mb-3 border-b border-border pb-2.5">Break ageing</h2>
        {(ageing.data ?? []).length === 0 ? <p className="type-meta py-6">No open breaks.</p> : (
          <table className="w-full text-[13px]">
            <thead>
              <tr className="border-b border-border">
                <th className="type-label py-2 text-left">Type</th>
                {buckets.map((b) => <th key={b} className="type-label py-2 text-right">{b}</th>)}
              </tr>
            </thead>
            <tbody>
              {TYPES.filter((t) => ageing.data!.some((a) => a.break_type === t)).map((t) => (
                <tr key={t} className="border-b border-border">
                  <td className="py-2.5">{BREAK_TYPE_LABELS[t]}</td>
                  {buckets.map((b) => {
                    const cell = ageing.data!.find((a) => a.break_type === t && a.age_bucket === b)
                    return <td key={b} className="py-2.5 text-right tabular">{cell?.open_breaks ?? <span className="text-grey">0</span>}</td>
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  )
}

function DataQuality() {
  const dq = useQuery({ queryKey: ['dq-results', 'latest'], queryFn: () => analyticsApi.dqResults() })
  const etl = useQuery({ queryKey: ['etl-runs'], queryFn: analyticsApi.etlRuns })
  const rows = dq.data ?? []
  const failed = rows.filter((r) => r.status === 'FAILED')
  return (
    <>
      <p className="text-[14px]">
        {rows.length === 0 ? 'No checks recorded yet.' : `${rows.length} checks on run #${rows[0]!.etl_run_id}: ${failed.length} not passed (${failed.filter((f) => f.severity === 'CRITICAL').length} critical).`}
      </p>
      <ul className="mt-4 divide-y divide-border border-y border-border text-[13px]">
        {rows.map((r) => (
          <li key={r.check_name} className="grid grid-cols-1 gap-2 py-3 md:grid-cols-[110px_1fr_auto]">
            <span className="flex gap-1.5">
              <StatusTag status={r.status} />
            </span>
            <span>
              <span className="tabular">{r.check_name}</span>
              <span className="type-meta block">{r.details}</span>
            </span>
            <span className="flex items-center gap-3 md:justify-end">
              {r.expected !== null && <span className="type-meta tabular">expected {r.expected}, got {r.actual}</span>}
              {r.expected === null && r.actual !== null && <span className="type-meta tabular">{r.actual}</span>}
              <Tag tone={r.severity === 'CRITICAL' ? 'negative' : r.severity === 'WARNING' ? 'warn' : 'outline'}>{r.severity}</Tag>
            </span>
          </li>
        ))}
      </ul>
      <Section title="ETL runs">
        <ul className="divide-y divide-border text-[13px]">
          {(etl.data ?? []).map((e) => (
            <li key={e.id} className="grid grid-cols-[70px_1fr_auto] gap-3 py-2.5">
              <span className="tabular">#{e.id}</span>
              <span>
                <DateText value={e.run_date} /> <span className="type-meta">{e.full_refresh ? 'full refresh' : 'incremental'}</span>
                {e.message && <span className="block text-loss">{e.message}</span>}
              </span>
              <StatusTag status={e.status} />
            </li>
          ))}
        </ul>
      </Section>
    </>
  )
}

function nextDay(iso: string): string {
  return new Date(Date.parse(`${iso}T00:00:00Z`) + 86_400_000).toISOString().slice(0, 10)
}
