import { useQuery } from '@tanstack/react-query'
import { useNavigate, useSearchParams } from 'react-router'

import { toApiError } from '../../api/http'
import { reconApi, type BreakFilters } from '../../api/recon'
import type { BreakStatus, BreakType, ReconBreak } from '../../api/types'
import { DataTable } from '../../components/ui/DataTable'
import { FilterBar, FilterSelect, FilterText } from '../../components/ui/Filters'
import { PageHeader } from '../../components/ui/Layout'
import { columnsFor } from '../../components/ui/tableColumns'
import { StatusTag, Tag } from '../../components/ui/Tag'
import { DateText, Price, Quantity } from '../../components/ui/Values'
import { useDirectory, usePortfolioNames, useUserNames } from '../../hooks/reference'
import { cn } from '../../lib/cn'
import { BREAK_TYPE_LABELS } from './breakLabels'

const STATUSES: BreakStatus[] = ['OPEN', 'IN_REVIEW', 'ESCALATED', 'RESOLVED']

export function BreaksPage() {
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const portfolioName = usePortfolioNames()
  const userName = useUserNames()
  const directory = useDirectory()

  const filters: BreakFilters = {
    status: (params.get('status') as BreakStatus | null) ?? undefined,
    type: (params.get('type') as BreakType | null) ?? undefined,
    runId: Number(params.get('runId')) || undefined,
    assignedTo: Number(params.get('assignedTo')) || undefined,
    minAgeDays: params.get('minAgeDays') ? Number(params.get('minAgeDays')) : undefined,
  }
  const set = (key: string, value: string | undefined) =>
    setParams((prev) => {
      const next = new URLSearchParams(prev)
      if (value) {
        next.set(key, value)
      } else {
        next.delete(key)
      }
      return next
    })

  const breaks = useQuery({ queryKey: ['breaks', filters], queryFn: () => reconApi.breaks(filters), refetchInterval: 20_000 })
  const error = breaks.error ? toApiError(breaks.error) : undefined

  const col = columnsFor<ReconBreak>()
  const columns = [
    col.accessor('age_days', {
      header: 'Age',
      cell: (c) => (
        <span className={cn('tabular', c.row.original.overdue && 'text-warn')}>
          {c.getValue()}d{c.row.original.overdue && <span className="sr-only"> past the SLA</span>}
        </span>
      ),
    }),
    col.accessor('break_type', { header: 'Type', cell: (c) => BREAK_TYPE_LABELS[c.getValue()] }),
    col.accessor('trade_ref', { header: 'Trade ref', cell: (c) => <span className="tabular">{c.getValue()}</span> }),
    col.accessor('isin', { header: 'ISIN', meta: { muted: true }, cell: (c) => <span className="tabular">{c.getValue()}</span> }),
    col.accessor('trade_date', { header: 'Trade date', meta: { muted: true }, cell: (c) => <DateText value={c.getValue()} /> }),
    col.accessor('portfolio_id', { header: 'Portfolio', cell: (c) => (c.getValue() ? portfolioName(c.getValue()) : <span className="text-grey">Unknown</span>) }),
    col.display({
      id: 'internal',
      header: 'Internal',
      meta: { align: 'right' },
      cell: (c) => <QtyPrice qty={c.row.original.internal_qty} price={c.row.original.internal_price} />,
    }),
    col.display({
      id: 'external',
      header: 'Custodian',
      meta: { align: 'right' },
      cell: (c) => <QtyPrice qty={c.row.original.external_qty} price={c.row.original.external_price} />,
    }),
    col.accessor('status', {
      header: 'Status',
      cell: (c) => (
        <span className="inline-flex items-center gap-1.5">
          <StatusTag status={c.getValue()} />
          {c.row.original.overdue && <Tag tone="warn">overdue</Tag>}
        </span>
      ),
    }),
    col.accessor('assigned_to', { header: 'Assignee', meta: { muted: true }, cell: (c) => (c.getValue() ? userName(c.getValue()) : '') }),
  ]

  const filtered = [...params.keys()].length > 0
  return (
    <>
      <PageHeader eyebrow="Reconciliation" title="Breaks." description="Differences between our settled trades and the custodian's records. Every action needs a comment and is audited." />
      <FilterBar onClear={filtered ? () => setParams({}) : undefined}>
        <FilterSelect label="Status" value={params.get('status') ?? ''} onChange={(v) => set('status', v)} options={STATUSES.map((s) => ({ value: s, label: s.replace('_', ' ').toLowerCase() }))} />
        <FilterSelect label="Type" value={params.get('type') ?? ''} onChange={(v) => set('type', v)} options={Object.entries(BREAK_TYPE_LABELS).map(([value, label]) => ({ value, label }))} />
        <FilterText label="Run" value={params.get('runId') ?? ''} onCommit={(v) => set('runId', v.replace(/\D/g, '') || undefined)} placeholder="Run number" />
        <FilterSelect
          label="Assignee"
          value={params.get('assignedTo') ?? ''}
          onChange={(v) => set('assignedTo', v)}
          options={(directory.data ?? []).filter((u) => u.role === 'OPS_ANALYST' || u.role === 'ADMIN').map((u) => ({ value: String(u.id), label: u.fullName }))}
        />
        <FilterSelect label="Older than" value={params.get('minAgeDays') ?? ''} onChange={(v) => set('minAgeDays', v)} options={[{ value: '1', label: '1 day' }, { value: '3', label: '3 days' }, { value: '7', label: '7 days' }]} />
      </FilterBar>
      <DataTable
        caption="Reconciliation breaks"
        columns={columns}
        data={breaks.data}
        loading={breaks.isLoading}
        error={error && { message: error.message, correlationId: error.correlationId }}
        onRetry={() => breaks.refetch()}
        empty={filtered ? 'No breaks match these filters.' : 'No breaks. Every settled trade matched the custodian.'}
        onRowClick={(b) => navigate(`/recon/breaks/${b.id}`)}
      />
    </>
  )
}

function QtyPrice({ qty, price }: { qty: number | null; price: number | null }) {
  if (qty === null) {
    return <span className="text-grey">none</span>
  }
  return (
    <span className="whitespace-nowrap">
      <Quantity value={qty} /> <span className="text-grey">@</span> <Price value={price} />
    </span>
  )
}
