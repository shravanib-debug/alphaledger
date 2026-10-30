import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'

import { toApiError } from '../../api/http'
import { positionApi } from '../../api/position'
import type { PositionRow, ValuationLine } from '../../api/types'
import { valuationApi } from '../../api/valuation'
import { ASSET_CLASS_LABELS } from '../../components/ui/chartTheme'
import { DataTable } from '../../components/ui/DataTable'
import { Figure, FigureRow, PageHeader } from '../../components/ui/Layout'
import { PortfolioPicker } from '../../components/ui/PortfolioPicker'
import { SourceBadge } from '../../components/ui/SourceBadge'
import { EmptyState } from '../../components/ui/States'
import { columnsFor } from '../../components/ui/tableColumns'
import { TabPanel, Tabs } from '../../components/ui/Tabs'
import { Money, Pct, Pnl, Price, Quantity } from '../../components/ui/Values'
import { useInstrumentsById, useSelectedPortfolio } from '../../hooks/reference'
import { compactRupees, marketToday, timestamp } from '../../lib/format'

const line = columnsFor<ValuationLine>()
const LINE_COLUMNS = [
  line.accessor('name', {
    header: 'Instrument',
    cell: (c) => (
      <span>
        <span className="block">{c.getValue()}</span>
        <span className="type-meta tabular">{c.row.original.isin}</span>
      </span>
    ),
  }),
  line.accessor('assetClass', { header: 'Asset class', meta: { muted: true }, cell: (c) => ASSET_CLASS_LABELS[c.getValue()] ?? c.getValue() }),
  line.accessor('quantity', { header: 'Quantity', meta: { align: 'right' }, cell: (c) => <Quantity value={c.getValue()} /> }),
  line.accessor('avgCost', { header: 'Avg cost (₹)', meta: { align: 'right' }, cell: (c) => <Price value={c.getValue()} /> }),
  line.accessor('price', {
    header: 'Price (₹)',
    meta: { align: 'right' },
    cell: (c) => {
      const r = c.row.original
      if (r.price === null) {
        return <span className="text-warn">unpriced</span>
      }
      return (
        <span className="inline-flex flex-wrap items-center justify-end gap-2">
          <Price value={r.price} />
          {r.priceSource && r.priceDate && <SourceBadge source={r.priceSource} dataDate={r.priceDate} stale={r.priceStale} fairValueLevel={r.fairValueLevel} />}
        </span>
      )
    },
  }),
  line.accessor('marketValue', { header: 'Market value (₹)', meta: { align: 'right' }, cell: (c) => <Money value={c.getValue()} reason="Unpriced: excluded from totals" /> }),
  line.accessor('unrealizedPnl', { header: 'Unrealized (₹)', meta: { align: 'right' }, cell: (c) => <Pnl value={c.getValue()} reason="Unpriced" /> }),
  line.accessor('realizedPnl', { header: 'Realized (₹)', meta: { align: 'right' }, cell: (c) => <Pnl value={c.getValue()} /> }),
  line.accessor('pctOfTotal', { header: 'Weight', meta: { align: 'right' }, cell: (c) => <Pct value={c.getValue()} /> }),
]

const snap = columnsFor<PositionRow & { name: string }>()
const SNAPSHOT_COLUMNS = [
  snap.accessor('name', { header: 'Instrument' }),
  snap.accessor('isin', { header: 'ISIN', meta: { muted: true }, cell: (c) => <span className="tabular">{c.getValue()}</span> }),
  snap.accessor('quantity', { header: 'Quantity', meta: { align: 'right' }, cell: (c) => <Quantity value={c.getValue()} /> }),
  snap.accessor('avgCost', { header: 'Avg cost (₹)', meta: { align: 'right' }, cell: (c) => <Price value={c.getValue()} /> }),
  snap.accessor('realizedPnl', { header: 'Realized (₹)', meta: { align: 'right' }, cell: (c) => <Pnl value={c.getValue()} /> }),
]

/** Holdings valued at the latest price, each with its price source and date (docs/ui-plan.md §6.5). */
export function PositionsPage() {
  const [portfolioId] = useSelectedPortfolio()
  const [tab, setTab] = useState('current')
  const [snapshotDate, setSnapshotDate] = useState(marketToday())
  const instruments = useInstrumentsById()

  const valuation = useQuery({
    queryKey: ['valuation', portfolioId, 'live'],
    queryFn: () => valuationApi.valuation(portfolioId!),
    enabled: portfolioId !== undefined,
    refetchInterval: 15_000,
  })
  const snapshot = useQuery({
    queryKey: ['position-snapshot', portfolioId, snapshotDate],
    queryFn: () => positionApi.snapshot(portfolioId!, snapshotDate),
    enabled: portfolioId !== undefined && tab === 'snapshots',
  })
  const exceptions = useQuery({
    queryKey: ['position-exceptions', portfolioId],
    queryFn: () => positionApi.exceptions(portfolioId!),
    enabled: portfolioId !== undefined && tab === 'exceptions',
  })

  const v = valuation.data?.details
  const error = valuation.error ? toApiError(valuation.error) : undefined

  return (
    <>
      <PageHeader eyebrow="Positions" title="Holdings and their value." description="Weighted-average cost, latest prices with their source, and P&L." aside={<PortfolioPicker />} />
      {portfolioId === undefined ? (
        <EmptyState message="No active portfolios yet." />
      ) : (
        <>
          <FigureRow>
            <Figure label="Market value" value={v ? compactRupees(v.marketValue) : '-'} />
            <Figure label="Cost" value={v ? compactRupees(v.totalCost) : '-'} />
            <Figure label="Unrealized P&L" value={v ? <Pnl value={v.unrealizedPnl} /> : '-'} />
            <Figure label="Realized P&L" value={v ? <Pnl value={v.realizedPnl} /> : '-'} />
            <Figure
              label="Pricing"
              value={v ? <span className="tabular">{v.pricedPositions} priced</span> : '-'}
              detail={v ? `${v.stalePositions} stale, ${v.unpricedPositions} unpriced` : undefined}
            />
          </FigureRow>
          <Tabs
            value={tab}
            onValueChange={setTab}
            tabs={[{ value: 'current', label: 'Current' }, { value: 'snapshots', label: 'Daily snapshots' }, { value: 'exceptions', label: 'Exceptions' }]}
          >
            <TabPanel value="current">
              <DataTable
                caption="Current positions"
                columns={LINE_COLUMNS}
                data={v?.lines}
                loading={valuation.isLoading}
                error={error && { message: error.message, correlationId: error.correlationId }}
                onRetry={() => valuation.refetch()}
                empty="No holdings in this portfolio yet."
              />
              {v && v.unpricedPositions > 0 && (
                <p className="type-meta mt-3">Unpriced holdings have no price on or before today and are left out of the totals rather than valued at zero.</p>
              )}
            </TabPanel>
            <TabPanel value="snapshots">
              <label className="mb-4 flex w-fit flex-col gap-1">
                <span className="type-meta">Snapshot date</span>
                <input type="date" value={snapshotDate} max={marketToday()} onChange={(e) => e.target.value && setSnapshotDate(e.target.value)} className="h-8 rounded-[var(--radius-control)] border border-border bg-ivory-raised px-2 text-[13px] tabular" />
              </label>
              <DataTable
                caption="Position snapshot"
                columns={SNAPSHOT_COLUMNS}
                data={snapshot.data?.map((r) => ({ ...r, name: instruments.get(r.instrumentId)?.name ?? r.isin }))}
                loading={snapshot.isLoading}
                empty="No snapshot for this date. Snapshots are taken each business day at 18:45 IST."
              />
            </TabPanel>
            <TabPanel value="exceptions">
              {(exceptions.data ?? []).length === 0 ? (
                <EmptyState message={exceptions.isLoading ? 'Loading' : 'No exceptions. Every trade was applied to the holdings.'} />
              ) : (
                <ul className="divide-y divide-border text-[13px]">
                  {exceptions.data!.map((e) => (
                    <li key={e.trade_id} className="flex flex-wrap gap-x-6 gap-y-1 py-3">
                      <span className="tabular">Trade {e.trade_id}</span>
                      <span>{instruments.get(e.instrument_id)?.name ?? `Instrument ${e.instrument_id}`}</span>
                      <span className="text-loss">{e.reason}</span>
                      <span className="type-meta ml-auto">{timestamp(e.created_at)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </TabPanel>
          </Tabs>
        </>
      )}
    </>
  )
}
