import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { toApiError } from '../../api/http'
import type { CashTransaction } from '../../api/types'
import { valuationApi } from '../../api/valuation'
import { can } from '../../auth/access'
import { useAuth } from '../../auth/AuthProvider'
import { Button } from '../../components/ui/Button'
import { DataTable } from '../../components/ui/DataTable'
import { Dialog } from '../../components/ui/Dialog'
import { Field, Input, NumberInput } from '../../components/ui/Field'
import { columnsFor } from '../../components/ui/tableColumns'
import { useToast } from '../../components/ui/Toast'
import { DateText, Money } from '../../components/ui/Values'
import { compactRupees, marketToday } from '../../lib/format'

const TYPE_LABELS: Record<CashTransaction['type'], string> = {
  DEPOSIT: 'Deposit',
  WITHDRAWAL: 'Withdrawal',
  TRADE_BUY: 'Settled buy',
  TRADE_SELL: 'Settled sell',
  LIABILITY_PAYMENT: 'Liability paid',
  BORROWING: 'Borrowing',
}

const col = columnsFor<CashTransaction>()
const COLUMNS = [
  col.accessor('effective_date', { header: 'Effective', cell: (c) => <DateText value={c.getValue()} /> }),
  col.accessor('type', { header: 'Type', cell: (c) => TYPE_LABELS[c.getValue()] ?? c.getValue() }),
  col.accessor('description', { header: 'Description', meta: { muted: true }, cell: (c) => c.getValue() ?? '' }),
  col.accessor('reference_id', { header: 'Reference', meta: { muted: true }, cell: (c) => <span className="tabular">{c.getValue() ?? ''}</span> }),
  col.accessor('amount', { header: 'Amount (₹)', meta: { align: 'right' }, cell: (c) => <Money value={c.getValue()} /> }),
]

/** The append-only cash ledger, with deposits and withdrawals for Admin and Ops. */
export function CashPanel({ portfolioId }: { portfolioId: number }) {
  const { user } = useAuth()
  const toast = useToast()
  const queryClient = useQueryClient()
  const [mode, setMode] = useState<'DEPOSIT' | 'WITHDRAWAL'>()
  const [amount, setAmount] = useState('')
  const [description, setDescription] = useState('')
  const [effectiveDate, setEffectiveDate] = useState(marketToday())
  const [formError, setFormError] = useState<string>()

  const cash = useQuery({ queryKey: ['cash', portfolioId], queryFn: () => valuationApi.cash(portfolioId) })
  const move = useMutation({
    mutationFn: () => valuationApi.moveCash(portfolioId, { type: mode!, amount: Number(amount), description: description || undefined, effectiveDate }),
    onSuccess: () => {
      toast.success(mode === 'DEPOSIT' ? 'Deposit recorded.' : 'Withdrawal recorded.')
      close()
      void queryClient.invalidateQueries({ queryKey: ['cash', portfolioId] })
      void queryClient.invalidateQueries({ queryKey: ['valuation'] })
    },
    onError: (err) => setFormError(toApiError(err).message),
  })

  const close = () => {
    setMode(undefined)
    setAmount('')
    setDescription('')
    setFormError(undefined)
    setEffectiveDate(marketToday())
  }
  const error = cash.error ? toApiError(cash.error) : undefined
  const validAmount = /^\d+(\.\d{1,2})?$/.test(amount) && Number(amount) > 0

  return (
    <>
      <div className="mb-4 flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="type-label">Balance</p>
          <p className="type-figure mt-2">{cash.data ? compactRupees(cash.data.balance) : '-'}</p>
        </div>
        {can(user?.role, 'cash.move') && (
          <div className="flex gap-2">
            <Button onClick={() => setMode('DEPOSIT')}>Deposit</Button>
            <Button onClick={() => setMode('WITHDRAWAL')}>Withdraw</Button>
          </div>
        )}
      </div>
      <DataTable
        caption="Cash ledger"
        columns={COLUMNS}
        data={cash.data?.transactions}
        loading={cash.isLoading}
        error={error && { message: error.message, correlationId: error.correlationId }}
        onRetry={() => cash.refetch()}
        empty="No cash movements yet. A deposit, or a settled trade, adds the first entry."
      />
      <Dialog
        open={mode !== undefined}
        onOpenChange={(o) => !o && close()}
        title={mode === 'DEPOSIT' ? 'Record a deposit' : 'Record a withdrawal'}
        description="Ledger entries are never edited; a mistake is corrected with a new entry."
        actions={
          <>
            <Button variant="ghost" onClick={close}>Cancel</Button>
            <Button variant="primary" disabled={!validAmount || move.isPending} onClick={() => move.mutate()}>Record</Button>
          </>
        }
      >
        <div className="space-y-4">
          <Field label="Amount (₹)" error={formError ?? (amount && !validAmount ? 'A positive amount with up to 2 decimals' : undefined)}>
            {(a) => <NumberInput {...a} value={amount} onChange={(e) => setAmount(e.target.value)} autoFocus />}
          </Field>
          <Field label="Effective date">
            {(a) => <Input {...a} type="date" className="tabular" value={effectiveDate} onChange={(e) => setEffectiveDate(e.target.value)} />}
          </Field>
          <Field label="Description (optional)">
            {(a) => <Input {...a} maxLength={200} value={description} onChange={(e) => setDescription(e.target.value)} />}
          </Field>
        </div>
      </Dialog>
    </>
  )
}
