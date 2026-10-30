import { Plus } from '@phosphor-icons/react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate, useSearchParams } from 'react-router'

import { toApiError } from '../../api/http'
import { tradeApi, type TradeFilters } from '../../api/trade'
import type { TradeSide, TradeStatus } from '../../api/types'
import { can } from '../../auth/access'
import { useAuth } from '../../auth/AuthProvider'
import { ButtonLink } from '../../components/ui/Button'
import { DataTable } from '../../components/ui/DataTable'
import { PageHeader } from '../../components/ui/Layout'
import { FilterBar, FilterDate, FilterSelect, FilterText } from '../../components/ui/Filters'
import { useInstrumentsById, usePortfolioNames, usePortfolios } from '../../hooks/reference'
import { tradeColumns } from './tradeColumns'

const SIZE = 50
const STATUSES: TradeStatus[] = ['VALIDATED', 'SETTLED', 'REJECTED', 'CANCELLED']

/** The trade blotter: filters live in the URL, paging is server-side (docs/ui-plan.md §6.4). */
export function TradesPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const portfolios = usePortfolios()
  const instruments = useInstrumentsById()
  const portfolioName = usePortfolioNames()

  const filters: TradeFilters = {
    portfolioId: num(params.get('portfolio')),
    status: (params.get('status') as TradeStatus | null) ?? undefined,
    side: (params.get('side') as TradeSide | null) ?? undefined,
    isin: params.get('isin') ?? undefined,
    from: params.get('from') ?? undefined,
    to: params.get('to') ?? undefined,
    page: num(params.get('page')) ?? 0,
    size: SIZE,
  }
  const set = (key: string, value: string | undefined) =>
    setParams((prev) => {
      const next = new URLSearchParams(prev)
      if (value) {
        next.set(key, value)
      } else {
        next.delete(key)
      }
      if (key !== 'page') {
        next.delete('page')
      }
      return next
    })

  const trades = useQuery({ queryKey: ['trades', filters], queryFn: () => tradeApi.list(filters), refetchInterval: 15_000, placeholderData: (prev) => prev })
  const error = trades.error ? toApiError(trades.error) : undefined
  const filtered = [...params.keys()].some((k) => k !== 'page')

  return (
    <>
      <PageHeader
        eyebrow="Trades"
        title="Trade blotter."
        description="Every trade captured, including rejected ones, with its settlement status."
        aside={
          can(user?.role, 'trade.create') && (
            <ButtonLink to="/trades/new" variant="primary">
              <Plus size={14} /> New trade
            </ButtonLink>
          )
        }
      />
      <FilterBar onClear={filtered ? () => setParams({}) : undefined}>
        <FilterSelect
          label="Portfolio"
          value={params.get('portfolio') ?? ''}
          onChange={(v) => set('portfolio', v)}
          options={(portfolios.data ?? []).map((p) => ({ value: String(p.id), label: p.name }))}
        />
        <FilterSelect label="Status" value={params.get('status') ?? ''} onChange={(v) => set('status', v)} options={STATUSES.map((s) => ({ value: s, label: s.charAt(0) + s.slice(1).toLowerCase() }))} />
        <FilterSelect label="Side" value={params.get('side') ?? ''} onChange={(v) => set('side', v)} options={[{ value: 'BUY', label: 'Buy' }, { value: 'SELL', label: 'Sell' }]} />
        <FilterText label="ISIN" value={params.get('isin') ?? ''} onCommit={(v) => set('isin', v.trim().toUpperCase() || undefined)} placeholder="INE..." />
        <FilterDate label="From" value={params.get('from') ?? ''} onChange={(v) => set('from', v)} />
        <FilterDate label="To" value={params.get('to') ?? ''} onChange={(v) => set('to', v)} />
      </FilterBar>
      <DataTable
        caption="Trades"
        columns={tradeColumns(instruments, portfolioName)}
        data={trades.data?.content}
        loading={trades.isLoading}
        error={error && { message: error.message, correlationId: error.correlationId }}
        onRetry={() => trades.refetch()}
        empty={filtered ? 'No trades match these filters.' : 'No trades yet.'}
        onRowClick={(t) => navigate(`/trades/${t.id}`)}
        paging={trades.data && { page: trades.data.page, size: SIZE, totalElements: trades.data.totalElements, onPageChange: (p) => set('page', String(p)) }}
      />
    </>
  )
}

function num(v: string | null): number | undefined {
  const n = Number(v)
  return v !== null && v !== '' && Number.isFinite(n) ? n : undefined
}
