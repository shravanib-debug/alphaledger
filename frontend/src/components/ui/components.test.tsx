import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import { DataTable } from './DataTable'
import { columnsFor } from './tableColumns'
import { Field, Input } from './Field'
import { SourceBadge } from './SourceBadge'
import { StatusTag } from './Tag'
import { TooltipProvider } from './Tooltip'
import { Missing, Money, Pnl } from './Values'

const wrap = (ui: React.ReactElement) => render(<TooltipProvider>{ui}</TooltipProvider>)

describe('values never pretend', () => {
  it('shows a missing value as a dash with its reason, not as zero', () => {
    wrap(<Money value={null} reason="Unpriced: no price on or before this date" />)
    expect(screen.getByLabelText('Not available: Unpriced: no price on or before this date')).toHaveTextContent('-')
    expect(screen.queryByText('0.00')).not.toBeInTheDocument()
  })

  it('gives P&L a sign and words, not only colour', () => {
    const { container } = wrap(
      <>
        <Pnl value={5000} />
        <Pnl value={-2540} />
      </>,
    )
    expect(container).toHaveTextContent('gain +5,000.00')
    expect(container).toHaveTextContent('loss -2,540.00')
  })

  it('keeps a negative amount visibly negative', () => {
    wrap(<Money value={-12540} compact />)
    expect(screen.getByText('-₹ 12,540.00')).toHaveClass('text-loss')
  })

  it('renders the Missing component standalone', () => {
    wrap(<Missing reason="No snapshot yet" />)
    expect(screen.getByLabelText('Not available: No snapshot yet')).toBeInTheDocument()
  })
})

describe('status and source labels carry their meaning in text', () => {
  it('spells out statuses', () => {
    render(<StatusTag status="IN_REVIEW" />)
    expect(screen.getByText('IN REVIEW')).toBeInTheDocument()
  })

  it('says stale in words', () => {
    wrap(<SourceBadge source="NSE_BHAVCOPY" dataDate="2026-10-14" stale />)
    expect(screen.getByText('NSE')).toBeInTheDocument()
    expect(screen.getByText('14 Oct 2026')).toBeInTheDocument()
    expect(screen.getByText('stale')).toBeInTheDocument()
  })
})

describe('form fields', () => {
  it('labels the control and announces errors', () => {
    render(<Field label="Quantity" error="SELL exceeds the current holding of 500.">{(a) => <Input {...a} />}</Field>)
    const input = screen.getByLabelText('Quantity')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription('SELL exceeds the current holding of 500.')
    expect(screen.getByRole('alert')).toHaveTextContent('SELL exceeds')
  })

  it('describes the control with helper text when there is no error', () => {
    render(<Field label="ISIN" helper="12 characters.">{(a) => <Input {...a} />}</Field>)
    const input = screen.getByLabelText('ISIN')
    expect(input).not.toHaveAttribute('aria-invalid')
    expect(input).toHaveAccessibleDescription('12 characters.')
  })
})

interface Row {
  ref: string
  notional: number
}
const col = columnsFor<Row>()
const columns = [
  col.accessor('ref', { header: 'Ref' }),
  col.accessor('notional', { header: 'Notional', meta: { align: 'right' }, cell: (c) => <Money value={c.getValue()} /> }),
]

describe('data table', () => {
  it('renders rows with numeric columns right-aligned', () => {
    wrap(<DataTable columns={columns} data={[{ ref: 'TR-101', notional: 215000 }]} empty="No trades." caption="Trades" />)
    expect(screen.getByRole('table', { name: 'Trades' })).toBeInTheDocument()
    expect(screen.getByText('2,15,000.00').closest('td')).toHaveClass('text-right')
  })

  it('shows the empty, loading and error states', () => {
    const { rerender } = wrap(<DataTable columns={columns} data={[]} empty="No trades match these filters." caption="Trades" />)
    expect(screen.getByText('No trades match these filters.')).toBeInTheDocument()

    rerender(<TooltipProvider><DataTable columns={columns} data={undefined} loading empty="x" caption="Trades" /></TooltipProvider>)
    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument()

    rerender(
      <TooltipProvider>
        <DataTable columns={columns} data={undefined} error={{ message: 'Trade service is unavailable.', correlationId: 'abc-123' }} empty="x" caption="Trades" />
      </TooltipProvider>,
    )
    expect(screen.getByRole('alert')).toHaveTextContent('Trade service is unavailable.')
    expect(screen.getByText('Reference abc-123')).toBeInTheDocument()
  })

  it('pages through server results', async () => {
    const pages: number[] = []
    wrap(
      <DataTable
        columns={columns}
        data={[{ ref: 'TR-1', notional: 1 }]}
        empty="x"
        caption="Trades"
        paging={{ page: 0, size: 50, totalElements: 120, onPageChange: (p) => pages.push(p) }}
      />,
    )
    expect(screen.getByText('1-50 of 120')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }))
    expect(pages).toEqual([1])
  })
})
