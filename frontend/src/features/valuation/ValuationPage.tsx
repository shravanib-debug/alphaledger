import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'

import { toApiError } from '../../api/http'
import { valuationApi } from '../../api/valuation'
import { ValueChart } from '../../components/ui/Charts'
import { ASSET_CLASS_LABELS } from '../../components/ui/chartTheme'
import { PageHeader, Panel } from '../../components/ui/Layout'
import { PortfolioPicker } from '../../components/ui/PortfolioPicker'
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/States'
import { TabPanel, Tabs } from '../../components/ui/Tabs'
import { Tag } from '../../components/ui/Tag'
import { DateText, Pct } from '../../components/ui/Values'
import { useSelectedPortfolio } from '../../hooks/reference'
import { compactRupees, marketToday } from '../../lib/format'
import { CashPanel } from './CashPanel'
import { LiabilitiesPanel } from './LiabilitiesPanel'

/** NAV and its parts, exposure against limits, history, cash and liabilities (docs/ui-plan.md §6.6). */
export function ValuationPage() {
  const [portfolioId] = useSelectedPortfolio()
  const [tab, setTab] = useState('nav')

  const valuation = useQuery({
    queryKey: ['valuation', portfolioId, 'live'],
    queryFn: () => valuationApi.valuation(portfolioId!),
    enabled: portfolioId !== undefined,
    refetchInterval: 15_000,
  })
  const history = useQuery({
    queryKey: ['valuation-history', portfolioId],
    queryFn: () => valuationApi.history(portfolioId!, '2020-01-01', marketToday()),
    enabled: portfolioId !== undefined,
  })
  const rules = useQuery({ queryKey: ['limit-rules'], queryFn: valuationApi.limitRules })
  const breaches = useQuery({ queryKey: ['limit-breaches', portfolioId], queryFn: () => valuationApi.limitBreaches(portfolioId), enabled: portfolioId !== undefined })

  if (portfolioId === undefined) {
    return (
      <>
        <PageHeader eyebrow="Valuation & NAV" title="What the portfolio is worth." aside={<PortfolioPicker />} />
        <EmptyState message="No active portfolios yet." />
      </>
    )
  }

  const v = valuation.data
  const error = valuation.error ? toApiError(valuation.error) : undefined
  const limitFor = (assetClass: string) => {
    const own = rules.data?.find((r) => r.portfolio_id === portfolioId && r.asset_class === assetClass)
    return own ?? rules.data?.find((r) => r.portfolio_id === null && r.asset_class === assetClass)
  }

  return (
    <>
      <PageHeader
        eyebrow="Valuation & NAV"
        title="What the portfolio is worth."
        description="NAV is market value plus cash, less liabilities. Cash moves when trades settle."
        aside={<PortfolioPicker />}
      />

      {error ? (
        <div className="mt-6"><ErrorState message={error.message} correlationId={error.correlationId} onRetry={() => valuation.refetch()} /></div>
      ) : (
        <div className="mt-8 grid grid-cols-2 items-end gap-y-6 border-b border-border pb-8 md:grid-cols-[1fr_auto_1fr_auto_1fr_auto_1.2fr]">
          <Term label="Market value" value={v?.marketValue} />
          <Op>+</Op>
          <Term label="Cash" value={v?.cashBalance} />
          <Op>-</Op>
          <Term label="Liabilities" value={v?.totalLiabilities} />
          <Op>=</Op>
          <div>
            <p className="type-label text-burgundy">NAV</p>
            <p className={`mt-2 font-serif text-[36px] leading-10 tabular ${v?.nav != null && v.nav < 0 ? 'text-loss' : ''}`}>
              {v ? (v.nav === null ? '-' : compactRupees(v.nav)) : <Skeleton className="h-9 w-40" />}
            </p>
            <p className="type-meta mt-1">{v ? `Basis ${v.valuationBasis.replace('_', ' ').toLowerCase()}, as of ` : ''}{v && <DateText value={v.asOfDate} />}</p>
          </div>
        </div>
      )}

      <div className="mt-6">
        <Tabs
          value={tab}
          onValueChange={setTab}
          tabs={[{ value: 'nav', label: 'Exposure & history' }, { value: 'cash', label: 'Cash' }, { value: 'liabilities', label: 'Liabilities' }]}
        >
          <TabPanel value="nav">
            <div className="grid grid-cols-1 gap-8 xl:grid-cols-[1fr_1.2fr]">
              <section>
                <h2 className="type-section mb-3 border-b border-border pb-2.5">Exposure by asset class</h2>
                {(v?.details.exposureByAssetClass ?? []).length === 0 ? (
                  <p className="type-meta py-6">No priced holdings.</p>
                ) : (
                  <table className="w-full text-[13px]">
                    <thead>
                      <tr className="border-b border-border">
                        <th className="type-label py-2 text-left">Asset class</th>
                        <th className="type-label py-2 text-right">Market value (₹)</th>
                        <th className="type-label py-2 text-right">Share</th>
                        <th className="type-label py-2 text-right">Limit</th>
                      </tr>
                    </thead>
                    <tbody>
                      {v!.details.exposureByAssetClass.map((e) => {
                        const limit = limitFor(e.key)
                        const over = limit !== undefined && e.pctOfTotal > limit.max_pct
                        return (
                          <tr key={e.key} className="border-b border-border">
                            <td className="py-2.5">{ASSET_CLASS_LABELS[e.key] ?? e.key}</td>
                            <td className="py-2.5 text-right tabular">{compactRupees(e.marketValue).replace('₹ ', '')}</td>
                            <td className={`py-2.5 text-right ${over ? 'text-loss' : ''}`}><Pct value={e.pctOfTotal} /></td>
                            <td className="py-2.5 text-right">
                              {limit ? <span className="inline-flex items-center gap-2">{over && <Tag tone="negative">over</Tag>}<Pct value={limit.max_pct} /></span> : <span className="text-grey">none</span>}
                            </td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                )}
                {(breaches.data ?? []).length > 0 && (
                  <div className="mt-6">
                    <p className="type-label">Recorded breaches</p>
                    <ul className="mt-2 divide-y divide-border text-[13px]">
                      {breaches.data!.slice(0, 6).map((b) => (
                        <li key={b.id} className="flex justify-between gap-4 py-2">
                          <span><DateText value={b.as_of_date} /> &nbsp; {ASSET_CLASS_LABELS[b.asset_class] ?? b.asset_class}</span>
                          <span className="tabular text-loss"><Pct value={b.exposure_pct} /> <span className="text-grey">of max</span> <Pct value={b.max_pct} /></span>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </section>
              <Panel>
                <h2 className="type-section mb-4">NAV history</h2>
                {(history.data ?? []).length < 2 ? (
                  <p className="type-description py-12 text-center text-[15px]">History appears after two end-of-day snapshots (each business day at 23:45 IST).</p>
                ) : (
                  <ValueChart points={history.data!.map((s) => ({ date: s.as_of_date, value: s.nav ?? s.market_value }))} label="NAV" />
                )}
              </Panel>
            </div>
          </TabPanel>
          <TabPanel value="cash">
            <CashPanel portfolioId={portfolioId} />
          </TabPanel>
          <TabPanel value="liabilities">
            <LiabilitiesPanel portfolioId={portfolioId} />
          </TabPanel>
        </Tabs>
      </div>
    </>
  )
}

function Term({ label, value }: { label: string; value: number | undefined }) {
  return (
    <div>
      <p className="type-label">{label}</p>
      <p className={`type-figure mt-2 ${value !== undefined && value < 0 ? 'text-loss' : ''}`}>{value === undefined ? <Skeleton className="h-7 w-28" /> : compactRupees(value)}</p>
    </div>
  )
}

function Op({ children }: { children: string }) {
  return <span aria-hidden className="hidden px-4 pb-1 font-serif text-[24px] text-grey md:block">{children}</span>
}
