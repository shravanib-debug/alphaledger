import { CaretLeft, CaretRight } from '@phosphor-icons/react'
import { useTable, type ColumnDef, type RowData } from '@tanstack/react-table'
import type { ReactNode } from 'react'

import { cn } from '../../lib/cn'
import { Button } from './Button'
import { EmptyState, ErrorState, SkeletonRows } from './States'
import { dataTableFeatures, type DataTableFeatures } from './tableColumns'

export interface Paging {
  page: number
  size: number
  totalElements: number
  onPageChange: (page: number) => void
}

/**
 * The standard table: uppercase grey headers, hairline rows, numbers right-aligned in tabular figures, sticky
 * header, server pagination, and built-in loading, empty and error states.
 */
export function DataTable<TRow extends RowData>({ columns, data, loading, error, onRetry, empty, paging, onRowClick, caption }: {
  columns: ColumnDef<DataTableFeatures, TRow, any>[]
  data: TRow[] | undefined
  loading?: boolean
  error?: { message: string; correlationId?: string }
  onRetry?: () => void
  empty: string
  paging?: Paging
  onRowClick?: (row: TRow) => void
  caption: string
}) {
  const table = useTable({ features: dataTableFeatures, columns, data: data ?? [] })

  if (error) {
    return <ErrorState message={error.message} correlationId={error.correlationId} onRetry={onRetry} />
  }
  if (loading && !data) {
    return <SkeletonRows columns={Math.min(columns.length, 8)} />
  }
  if (!data || data.length === 0) {
    return <EmptyState message={empty} />
  }

  return (
    <div>
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-[13px] leading-[18px]">
          <caption className="sr-only">{caption}</caption>
          <thead className="sticky top-0 z-10 bg-ivory-raised">
            {table.getHeaderGroups().map((group) => (
              <tr key={group.id}>
                {group.headers.map((header) => {
                  const meta = header.column.columnDef.meta
                  return (
                    <th
                      key={header.id}
                      scope="col"
                      className={cn(
                        'type-label whitespace-nowrap border-b border-border px-3 py-2.5 font-medium first:pl-0 last:pr-0',
                        meta?.align === 'right' ? 'text-right' : 'text-left',
                      )}
                    >
                      {header.isPlaceholder ? null : <table.FlexRender header={header} />}
                    </th>
                  )
                })}
              </tr>
            ))}
          </thead>
          <tbody>
            {table.getRowModel().rows.map((row) => (
              <tr
                key={row.id}
                onClick={onRowClick ? () => onRowClick(row.original) : undefined}
                className={cn(
                  'border-b border-border transition-colors duration-[120ms] hover:bg-taupe/20',
                  onRowClick && 'cursor-pointer',
                )}
              >
                {row.getAllCells().map((cell) => {
                  const meta = cell.column.columnDef.meta
                  return (
                    <td
                      key={cell.id}
                      className={cn(
                        'px-3 py-2.5 align-middle first:pl-0 last:pr-0',
                        meta?.align === 'right' && 'tabular text-right',
                        meta?.muted && 'text-grey',
                      )}
                    >
                      <table.FlexRender cell={cell} />
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {paging && <Pager {...paging} />}
    </div>
  )
}

function Pager({ page, size, totalElements, onPageChange }: Paging) {
  const pages = Math.max(1, Math.ceil(totalElements / size))
  const from = totalElements === 0 ? 0 : page * size + 1
  const to = Math.min(totalElements, (page + 1) * size)
  return (
    <nav aria-label="Pagination" className="flex items-center justify-between pt-3 type-meta">
      <span className="tabular">
        {from}-{to} of {totalElements}
      </span>
      <span className="flex items-center gap-1">
        <Button size="sm" variant="ghost" aria-label="Previous page" disabled={page === 0} onClick={() => onPageChange(page - 1)}>
          <CaretLeft size={13} />
        </Button>
        <span className="tabular px-1">
          {page + 1} / {pages}
        </span>
        <Button size="sm" variant="ghost" aria-label="Next page" disabled={page + 1 >= pages} onClick={() => onPageChange(page + 1)}>
          <CaretRight size={13} />
        </Button>
      </span>
    </nav>
  )
}

/** Header text that is right-aligned with its numeric column. */
export const NumericHeader = ({ children }: { children: ReactNode }) => <span className="block text-right">{children}</span>
