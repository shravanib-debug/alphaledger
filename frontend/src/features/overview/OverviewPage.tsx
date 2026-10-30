import { ArrowRight, Bank, ClockCountdown, Database, WarningDiamond } from '@phosphor-icons/react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'

import { analyticsApi } from '../../api/analytics'
import { toApiError } from '../../api/http'
import { reconApi } from '../../api/recon'
import { tradeApi } from '../../api/trade'
import { valuationApi } from '../../api/valuation'
import { canRead } from '../../auth/access'
import { useAuth } from '../../auth/AuthProvider'
import { Donut, ValueChart } from '../../components/ui/Charts'
import { ASSET_CLASS_LABELS } from '../../components/ui/chartTheme'
import { Figure, FigureRow, PageHeader, Panel, Section } from '../../components/ui/Layout'
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/States'
import { StatusStrip } from '../../components/ui/StatusStrip'
import { StatusTag } from '../../components/ui/Tag'
import { Pnl } from '../../components/ui/Values'
import { usePortfolios } from '../../hooks/reference'
import { compactRupees, marketToday, timestamp } from '../../lib/format'
import { sumAmounts } from '../../lib/money'
import { RecentTrades } from './RecentTrades'

const RANGES = [
  { key: '1M', days: 31 },
  { key: '3M', days: 92 },
  { key: '6M', days: 183 },
  { key: '1Y', days: 366 },
]

/** The reference screen (docs/ui-plan.md §6.2): every figure is a sum of real per-portfolio API responses. */
export function OverviewPage() {
  const { user } = useAuth()
  const [asOf, setAsOf] = useState(marketToday())
  const [range, setRange] = useState('ALL')
  const portfolios = usePortfolios()
  const active = (portfolios.data ?? []).filter((p) => p.status === 'ACTIVE')

  const valuations = useQueries({
    queries: active.map((p) => ({
      queryKey: ['valuation', p.id, asOf],
      queryFn: () => valuationApi.valuation(p.id, asOf),
      refetchInterval: 15_000,
    })),
  })
  const histories = useQueries({
    queries: active.map((p) => ({
      queryKey: ['valuation-history', p.id],
      queryFn: () => valuationApi.history(p.id, '2020-01-01', marketToday()),
    })),
  })

  const loaded = valuations.length > 0 && valuations.every((v) => v.data)
  const failed = valuations.find((v) => v.error)
  const vals = valuations.map((v) => v.data).filter((v) => v !== undefined)

  const totals = useMemo(
    () => ({
      nav: sumAmounts(vals.map((v) => v.nav)),
      marketValue: sumAmounts(vals.map((v) => v.marketValue)),
      cash: sumAmounts(vals.map((v) => v.cashBalance)),
      liabilities: sumAmounts(vals.map((v) => v.totalLiabilities)),
      unrealized: sumAmounts(vals.map((v) => v.details.unrealizedPnl)),
      realized: sumAmounts(vals.map((v) => v.details.realizedPnl)),
    }),
    [vals],
  )

  const allocation = useMemo(() => {
    const byClass = new Map<string, number>()
    for (const v of vals) {
      for (const e of v.details.exposureByAssetClass) {
        byClass.set(e.key, sumAmounts([byClass.get(e.key), e.marketValue]))
      }
    }
    return [...byClass.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([key, value]) => ({ label: ASSET_CLASS_LABELS[key] ?? key, value }))
  }, [vals])

  // NAV per end-of-day snapshot date, summed across portfolios.
  const navSeries = useMemo(() => {
    const byDate = new Map<string, number>()
    for (const h of histories) {
      for (const s of h.data ?? []) {
        byDate.set(s.as_of_date, sumAmounts([byDate.get(s.as_of_date), s.nav ?? s.market_value]))
      }
    }
    return [...byDate.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([date, value]) => ({ date, value }))
  }, [histories])
  const spanDays = navSeries.length > 1 ? daysBetween(navSeries[0]!.date, navSeries[navSeries.length - 1]!.date) : 0
  const availableRanges = RANGES.filter((r) => spanDays >= r.days * 0.8)
  const shownSeries = (() => {
    const r = RANGES.find((x) => x.key === range)
    if (!r || navSeries.length === 0) {
      return navSeries
    }
    const last = navSeries[navSeries.length - 1]!.date
    return navSeries.filter((p) => daysBetween(p.date, last) <= r.days)
  })()
  const previousNav = navSeries.filter((p) => p.date < asOf).at(-1)

  const pending = useQuery({ queryKey: ['trades', 'pending-count'], queryFn: () => tradeApi.list({ status: 'VALIDATED', size: 1 }) })
  const seesRecon = canRead(user?.role, 'recon')
  const openBreaks = useQuery({ queryKey: ['breaks', 'open-count'], queryFn: () => reconApi.breaks({ status: 'OPEN' }), enabled: seesRecon })
  const breaches = useQuery({ queryKey: ['limit-breaches'], queryFn: () => valuationApi.limitBreaches(), enabled: !seesRecon })
  const etl = useQuery({ queryKey: ['etl-runs'], queryFn: analyticsApi.etlRuns })
  const dq = useQuery({ queryKey: ['dq-results', 'latest'], queryFn: () => analyticsApi.dqResults() })
  const latestEtl = etl.data?.[0]
  const failedChecks = (dq.data ?? []).filter((d) => d.status === 'FAILED' && d.severity !== 'INFO').length

  return (
    <>
      <PageHeader
        eyebrow="Portfolio overview"
        title="Your portfolios, at a glance."
        description="Positions, valuation and operational status across your funds."
        aside={
          <label className="flex flex-col gap-1">
            <span className="type-meta">As of</span>
            <input
              type="date"
              value={asOf}
              max={marketToday()}
              onChange={(e) => e.target.value && setAsOf(e.target.value)}
              className="h-9 rounded-[var(--radius-control)] border border-border bg-ivory-raised px-2.5 text-[13px] tabular focus:border-burgundy focus:outline-none focus:ring-1 focus:ring-burgundy"
            />
          </label>
        }
      />

      {failed ? (
        <div className="mt-6">
          <ErrorState message={toApiError(failed.error).message} correlationId={toApiError(failed.error).correlationId} onRetry={() => failed.refetch()} />
        </div>
      ) : portfolios.data && active.length === 0 ? (
        <EmptyState message="No active portfolios yet." action={<Link to="/portfolios" className="text-[13px] text-burgundy">Go to portfolios</Link>} />
      ) : (
        <FigureRow>
          <Figure
            label="Total NAV"
            value={loaded ? <span className={totals.nav < 0 ? 'text-loss' : ''}>{compactRupees(totals.nav)}</span> : <Skeleton className="mt-2 h-7 w-36" />}
            detail={
              loaded && previousNav ? (
                <span>
                  <Pnl value={sumAmounts([totals.nav, -previousNav.value])} /> vs. the previous snapshot
                </span>
              ) : loaded ? (
                'Market value + cash - liabilities'
              ) : undefined
            }
          />
          <Figure label="Market value" value={loaded ? compactRupees(totals.marketValue) : <Skeleton className="mt-2 h-7 w-32" />} />
          <Figure label="Cash" value={loaded ? compactRupees(totals.cash) : <Skeleton className="mt-2 h-7 w-28" />} />
          <Figure label="Liabilities" value={loaded ? compactRupees(totals.liabilities) : <Skeleton className="mt-2 h-7 w-28" />} />
          <Figure
            label="Unrealized P&L"
            value={loaded ? <Pnl value={totals.unrealized} /> : <Skeleton className="mt-2 h-7 w-28" />}
            detail={loaded ? <span>Realized <Pnl value={totals.realized} /></span> : undefined}
          />
        </FigureRow>
      )}

      <div className="mt-4 grid grid-cols-1 gap-6 xl:grid-cols-[1.7fr_1fr]">
        <Panel>
          <div className="mb-4 flex flex-wrap items-baseline justify-between gap-3">
            <h2 className="type-section">Portfolio value</h2>
            {navSeries.length > 1 && (
              <div className="flex gap-4 text-[12px]">
                {[...availableRanges.map((r) => r.key), 'ALL'].map((k) => (
                  <button
                    key={k}
                    onClick={() => setRange(k)}
                    className={range === k ? 'border-b border-burgundy text-burgundy' : 'text-grey hover:text-espresso'}
                  >
                    {k === 'ALL' ? 'All' : k}
                  </button>
                ))}
              </div>
            )}
          </div>
          {histories.some((h) => h.isLoading) ? (
            <Skeleton className="h-60 w-full" />
          ) : shownSeries.length < 2 ? (
            <p className="type-description py-16 text-center text-[15px]">
              The value line appears once there are two end-of-day snapshots. They are taken each business day at 23:45 IST.
            </p>
          ) : (
            <ValueChart points={shownSeries} label="NAV" />
          )}
        </Panel>
        <Panel>
          <h2 className="type-section mb-4">AUM by asset class</h2>
          {!loaded ? (
            <Skeleton className="h-44 w-full" />
          ) : allocation.length === 0 ? (
            <p className="type-description py-12 text-[15px]">No priced holdings yet.</p>
          ) : (
            <Donut slices={allocation} />
          )}
        </Panel>
      </div>

      <Section title="Recent trades" actions={<Link to="/trades" className="inline-flex items-center gap-1.5 text-burgundy">View all trades <ArrowRight size={13} /></Link>}>
        <RecentTrades />
      </Section>

      <div className="mt-10 grid grid-cols-1 border-y border-border md:grid-cols-3">
        {seesRecon ? (
          <StatusStrip icon={Bank} label="Open reconciliation breaks" value={openBreaks.data?.length ?? '-'} detail={openBreaks.data ? `${openBreaks.data.filter((b) => b.overdue).length} past the SLA` : undefined} href="/recon/breaks?status=OPEN" />
        ) : (
          <StatusStrip icon={WarningDiamond} label="Limit breaches" value={breaches.data?.length ?? '-'} detail="Asset-class limits exceeded" href="/valuation" />
        )}
        <StatusStrip icon={ClockCountdown} label="Pending settlements" value={pending.data?.totalElements ?? '-'} detail="Trades awaiting T+1 settlement" href="/trades?status=VALIDATED" />
        <StatusStrip
          icon={Database}
          label="Latest ETL run"
          value={<span className="text-[16px] text-espresso">{latestEtl ? timestamp(latestEtl.started_at) : 'None yet'}</span>}
          detail={
            latestEtl ? (
              <span className="flex items-center gap-2">
                <StatusTag status={latestEtl.status} />
                {failedChecks === 0 ? 'All checks passed' : `${failedChecks} check${failedChecks === 1 ? '' : 's'} not passed`}
              </span>
            ) : (
              'Runs nightly at 02:00 IST'
            )
          }
          href="/analytics"
        />
      </div>
    </>
  )
}

function daysBetween(a: string, b: string): number {
  return Math.round((Date.parse(b) - Date.parse(a)) / 86_400_000)
}
