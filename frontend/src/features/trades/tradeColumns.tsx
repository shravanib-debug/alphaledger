import type { Instrument, Trade } from '../../api/types'
import { columnsFor } from '../../components/ui/tableColumns'
import { StatusTag } from '../../components/ui/Tag'
import { Tooltip } from '../../components/ui/Tooltip'
import { DateText, Money, Price, Quantity } from '../../components/ui/Values'

const col = columnsFor<Trade>()

/** Trade columns shared by the blotter and the overview's recent trades. */
export function tradeColumns(instruments: Map<number, Instrument>, portfolioName: (id: number) => string, compact = false) {
  const all = [
    col.accessor('tradeDate', { header: 'Date', cell: (c) => <DateText value={c.getValue()} /> }),
    col.accessor('tradeRef', { header: 'Ref', meta: { muted: true }, cell: (c) => <span className="tabular">{c.getValue()}</span> }),
    col.accessor('portfolioId', { header: 'Portfolio', cell: (c) => portfolioName(c.getValue()) }),
    col.accessor('isin', {
      header: 'Instrument',
      cell: (c) => {
        const instrument = c.row.original.instrumentId ? instruments.get(c.row.original.instrumentId) : undefined
        return (
          <span>
            <span className="block">{instrument?.name ?? 'Unknown instrument'}</span>
            <span className="type-meta tabular">{c.getValue()}</span>
          </span>
        )
      },
    }),
    col.accessor('side', {
      header: 'Side',
      cell: (c) => <span className={c.getValue() === 'BUY' ? 'text-gain' : 'text-loss'}>{c.getValue()}</span>,
    }),
    col.accessor('quantity', { header: 'Quantity', meta: { align: 'right' }, cell: (c) => <Quantity value={c.getValue()} /> }),
    col.accessor('price', { header: 'Price (₹)', meta: { align: 'right' }, cell: (c) => <Price value={c.getValue()} /> }),
    col.accessor('notional', { header: 'Value (₹)', meta: { align: 'right' }, cell: (c) => <Money value={c.getValue()} /> }),
    col.accessor('settleDate', { header: 'Settles', meta: { muted: true }, cell: (c) => <DateText value={c.getValue()} /> }),
    col.accessor('status', {
      header: 'Status',
      cell: (c) =>
        c.row.original.rejectionReason ? (
          <Tooltip content={c.row.original.rejectionReason}>
            <span className="cursor-help">
              <StatusTag status={c.getValue()} />
            </span>
          </Tooltip>
        ) : (
          <StatusTag status={c.getValue()} />
        ),
    }),
  ]
  return compact ? all.filter((c) => !['tradeRef', 'settleDate'].includes(c.accessorKey as string)) : all
}
