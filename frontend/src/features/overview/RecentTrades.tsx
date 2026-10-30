import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router'

import { toApiError } from '../../api/http'
import { tradeApi } from '../../api/trade'
import { DataTable } from '../../components/ui/DataTable'
import { useInstrumentsById, usePortfolioNames } from '../../hooks/reference'
import { tradeColumns } from '../trades/tradeColumns'

export function RecentTrades() {
  const navigate = useNavigate()
  const instruments = useInstrumentsById()
  const portfolioName = usePortfolioNames()
  const trades = useQuery({ queryKey: ['trades', 'recent'], queryFn: () => tradeApi.list({ size: 5 }), refetchInterval: 15_000 })
  const error = trades.error ? toApiError(trades.error) : undefined
  return (
    <DataTable
      caption="Recent trades"
      columns={tradeColumns(instruments, portfolioName, true)}
      data={trades.data?.content}
      loading={trades.isLoading}
      error={error && { message: error.message, correlationId: error.correlationId }}
      onRetry={() => trades.refetch()}
      empty="No trades yet."
      onRowClick={(t) => navigate(`/trades/${t.id}`)}
    />
  )
}
