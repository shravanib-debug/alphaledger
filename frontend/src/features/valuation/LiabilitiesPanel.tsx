import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { toApiError } from '../../api/http'
import type { Liability, LiabilityType } from '../../api/types'
import { valuationApi } from '../../api/valuation'
import { can } from '../../auth/access'
import { useAuth } from '../../auth/AuthProvider'
import { Button } from '../../components/ui/Button'
import { DataTable } from '../../components/ui/DataTable'
import { Dialog } from '../../components/ui/Dialog'
import { Field, Input, NumberInput } from '../../components/ui/Field'
import { Select } from '../../components/ui/Select'
import { columnsFor } from '../../components/ui/tableColumns'
import { StatusTag } from '../../components/ui/Tag'
import { useToast } from '../../components/ui/Toast'
import { DateText, Money } from '../../components/ui/Values'
import { marketToday } from '../../lib/format'

const TYPES: { value: LiabilityType; label: string }[] = [
  { value: 'EXPENSE', label: 'Expense' },
  { value: 'PAYABLE', label: 'Payable' },
  { value: 'MANAGEMENT_FEE', label: 'Management fee' },
  { value: 'BORROWING', label: 'Borrowing (adds the amount to cash)' },
]
const TYPE_LABEL = Object.fromEntries(TYPES.map((t) => [t.value, t.label.replace(/ \(.*\)$/, '')]))

/** Liabilities counted in NAV until paid; paying one moves it to cash, so NAV does not change. */
export function LiabilitiesPanel({ portfolioId }: { portfolioId: number }) {
  const { user } = useAuth()
  const toast = useToast()
  const queryClient = useQueryClient()
  const manage = can(user?.role, 'liability.manage')
  const [adding, setAdding] = useState(false)
  const [type, setType] = useState<LiabilityType>('EXPENSE')
  const [description, setDescription] = useState('')
  const [amount, setAmount] = useState('')
  const [accrualDate, setAccrualDate] = useState(marketToday())
  const [formError, setFormError] = useState<string>()

  const liabilities = useQuery({ queryKey: ['liabilities', portfolioId], queryFn: () => valuationApi.liabilities(portfolioId) })
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['liabilities', portfolioId] })
    void queryClient.invalidateQueries({ queryKey: ['cash', portfolioId] })
    void queryClient.invalidateQueries({ queryKey: ['valuation'] })
  }
  const onError = (err: unknown) => {
    const e = toApiError(err)
    toast.error(e.message, e.correlationId)
  }

  const add = useMutation({
    mutationFn: () => valuationApi.addLiability(portfolioId, { type, description: description.trim(), amount: Number(amount), accrualDate }),
    onSuccess: () => {
      toast.success('Liability recorded.')
      setAdding(false)
      setDescription('')
      setAmount('')
      setFormError(undefined)
      refresh()
    },
    onError: (err) => setFormError(toApiError(err).message),
  })
  const pay = useMutation({
    mutationFn: (l: Liability) => valuationApi.payLiability(portfolioId, l.id),
    onSuccess: () => {
      toast.success('Liability paid from cash.')
      refresh()
    },
    onError,
  })
  const remove = useMutation({
    mutationFn: (l: Liability) => valuationApi.deleteLiability(portfolioId, l.id),
    onSuccess: () => {
      toast.success('Liability removed.')
      refresh()
    },
    onError,
  })

  const col = columnsFor<Liability>()
  const columns = [
    col.accessor('accrual_date', { header: 'Accrued', cell: (c) => <DateText value={c.getValue()} /> }),
    col.accessor('type', { header: 'Type', cell: (c) => TYPE_LABEL[c.getValue()] ?? c.getValue() }),
    col.accessor('description', { header: 'Description', meta: { muted: true } }),
    col.accessor('status', { header: 'Status', cell: (c) => <StatusTag status={c.getValue()} /> }),
    col.accessor('paid_date', { header: 'Paid', meta: { muted: true }, cell: (c) => (c.getValue() ? <DateText value={c.getValue()} /> : '') }),
    col.accessor('amount', { header: 'Amount (₹)', meta: { align: 'right' }, cell: (c) => <Money value={c.getValue()} /> }),
    ...(manage
      ? [
          col.display({
            id: 'actions',
            header: '',
            meta: { align: 'right' as const },
            cell: (c) =>
              c.row.original.status === 'OPEN' ? (
                <span className="inline-flex gap-1">
                  <Button size="sm" disabled={pay.isPending} onClick={() => pay.mutate(c.row.original)}>Pay</Button>
                  {c.row.original.type !== 'BORROWING' && (
                    <Button size="sm" variant="ghost" disabled={remove.isPending} onClick={() => remove.mutate(c.row.original)}>Delete</Button>
                  )}
                </span>
              ) : null,
          }),
        ]
      : []),
  ]

  const error = liabilities.error ? toApiError(liabilities.error) : undefined
  const validAmount = /^\d+(\.\d{1,2})?$/.test(amount) && Number(amount) > 0

  return (
    <>
      {manage && (
        <div className="mb-4 flex justify-end">
          <Button onClick={() => setAdding(true)}>Add liability</Button>
        </div>
      )}
      <DataTable
        caption="Liabilities"
        columns={columns}
        data={liabilities.data}
        loading={liabilities.isLoading}
        error={error && { message: error.message, correlationId: error.correlationId }}
        onRetry={() => liabilities.refetch()}
        empty="No liabilities. Management fees accrue daily at 23:40 IST."
      />
      <p className="type-meta mt-3">A borrowing cannot be deleted or resized because its cash came in; it is repaid instead.</p>
      <Dialog
        open={adding}
        onOpenChange={setAdding}
        title="Add liability"
        actions={
          <>
            <Button variant="ghost" onClick={() => setAdding(false)}>Cancel</Button>
            <Button variant="primary" disabled={!validAmount || !description.trim() || add.isPending} onClick={() => add.mutate()}>Record</Button>
          </>
        }
      >
        <div className="space-y-4">
          <Field label="Type">
            {(a) => <Select {...a} value={type} onValueChange={(v) => setType(v as LiabilityType)} options={TYPES} />}
          </Field>
          <Field label="Description">
            {(a) => <Input {...a} maxLength={200} value={description} onChange={(e) => setDescription(e.target.value)} />}
          </Field>
          <div className="grid grid-cols-2 gap-4">
            <Field label="Amount (₹)" error={formError}>
              {(a) => <NumberInput {...a} value={amount} onChange={(e) => setAmount(e.target.value)} />}
            </Field>
            <Field label="Accrual date">
              {(a) => <Input {...a} type="date" className="tabular" value={accrualDate} onChange={(e) => setAccrualDate(e.target.value)} />}
            </Field>
          </div>
        </div>
      </Dialog>
    </>
  )
}
