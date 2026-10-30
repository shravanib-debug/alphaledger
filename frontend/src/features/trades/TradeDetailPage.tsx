import { ArrowLeft } from '@phosphor-icons/react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'

import { toApiError } from '../../api/http'
import { tradeApi } from '../../api/trade'
import { can } from '../../auth/access'
import { useAuth } from '../../auth/AuthProvider'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { Field, Textarea } from '../../components/ui/Field'
import { PageHeader, Section } from '../../components/ui/Layout'
import { ErrorState, SkeletonRows } from '../../components/ui/States'
import { StatusTag } from '../../components/ui/Tag'
import { useToast } from '../../components/ui/Toast'
import { DateText, Money, Price, Quantity } from '../../components/ui/Values'
import { useInstrumentsById, usePortfolioNames, useUserNames } from '../../hooks/reference'
import { timestamp } from '../../lib/format'

type Action = 'cancel' | 'settle'

export function TradeDetailPage() {
  const id = Number(useParams().id)
  const { user } = useAuth()
  const toast = useToast()
  const queryClient = useQueryClient()
  const instruments = useInstrumentsById()
  const portfolioName = usePortfolioNames()
  const userName = useUserNames()
  const [action, setAction] = useState<Action>()
  const [comment, setComment] = useState('')

  const trade = useQuery({ queryKey: ['trade', id], queryFn: () => tradeApi.get(id) })
  const audit = useQuery({ queryKey: ['trade-audit', id], queryFn: () => tradeApi.audit(id) })

  const act = useMutation({
    mutationFn: ({ kind, note }: { kind: Action; note: string }) => (kind === 'cancel' ? tradeApi.cancel(id, note) : tradeApi.settle(id, note)),
    onSuccess: (t) => {
      toast.success(t.status === 'CANCELLED' ? `Trade ${t.tradeRef} cancelled.` : `Trade ${t.tradeRef} settled.`)
      setAction(undefined)
      setComment('')
      void queryClient.invalidateQueries({ queryKey: ['trade', id] })
      void queryClient.invalidateQueries({ queryKey: ['trade-audit', id] })
      void queryClient.invalidateQueries({ queryKey: ['trades'] })
    },
    onError: (err) => {
      const e = toApiError(err)
      toast.error(e.message, e.correlationId)
    },
  })

  if (trade.error) {
    const e = toApiError(trade.error)
    return <ErrorState message={e.status === 404 ? `Trade ${id} not found.` : e.message} correlationId={e.correlationId} onRetry={() => trade.refetch()} />
  }
  if (!trade.data) {
    return <SkeletonRows rows={6} columns={2} />
  }

  const t = trade.data
  const instrument = t.instrumentId ? instruments.get(t.instrumentId) : undefined
  const open = t.status === 'VALIDATED'
  const canCancel = open && can(user?.role, 'trade.cancel')
  const canSettle = open && can(user?.role, 'trade.settle')

  return (
    <>
      <Link to="/trades" className="mb-4 inline-flex items-center gap-1.5 text-[13px] text-grey hover:text-espresso">
        <ArrowLeft size={13} /> Trade blotter
      </Link>
      <PageHeader
        eyebrow={`Trade ${t.tradeRef}`}
        title={`${t.side === 'BUY' ? 'Buy' : 'Sell'} ${instrument?.name ?? t.isin}.`}
        description={`${portfolioName(t.portfolioId)}, booked ${timestamp(t.createdAt)}.`}
        aside={
          <div className="flex items-center gap-2">
            <StatusTag status={t.status} />
            {canSettle && <Button onClick={() => setAction('settle')}>Settle now</Button>}
            {canCancel && <Button variant="danger" onClick={() => setAction('cancel')}>Cancel trade</Button>}
          </div>
        }
      />

      {t.rejectionReason && (
        <p role="note" className="mt-6 border-l-2 border-loss py-1 pl-4 text-[14px]">
          Rejected: {t.rejectionReason}
        </p>
      )}

      <dl className="mt-6 grid grid-cols-2 gap-x-10 md:grid-cols-4">
        <Item label="Quantity"><Quantity value={t.quantity} /></Item>
        <Item label="Price (₹)"><Price value={t.price} /></Item>
        <Item label="Notional (₹)"><Money value={t.notional} /></Item>
        <Item label="ISIN"><span className="tabular">{t.isin}</span></Item>
        <Item label="Trade date"><DateText value={t.tradeDate} /></Item>
        <Item label="Settle date"><DateText value={t.settleDate} /></Item>
        <Item label="Booked by">{userName(t.createdBy)}</Item>
        <Item label="Currency">{t.currency}</Item>
      </dl>

      <Section title="Status history">
        {audit.isLoading ? (
          <SkeletonRows rows={3} columns={3} />
        ) : (
          <ol className="relative ml-1.5 border-l border-border">
            {(audit.data ?? []).map((a) => (
              <li key={a.id} className="relative pb-5 pl-6 last:pb-0">
                <span className="absolute -left-[4.5px] top-1.5 h-2 w-2 rounded-full bg-taupe" aria-hidden />
                <div className="flex flex-wrap items-center gap-2">
                  {a.fromStatus && (
                    <>
                      <StatusTag status={a.fromStatus} />
                      <span className="text-grey" aria-label="to">to</span>
                    </>
                  )}
                  <StatusTag status={a.toStatus} />
                  <span className="type-meta">{timestamp(a.changedAt)}, {a.changedBy ? userName(a.changedBy) : 'system'}</span>
                </div>
                {a.comment && <p className="mt-1.5 text-[13px]">{a.comment}</p>}
              </li>
            ))}
          </ol>
        )}
      </Section>

      <Dialog
        open={action !== undefined}
        onOpenChange={(o) => !o && setAction(undefined)}
        title={action === 'cancel' ? 'Cancel trade' : 'Settle trade'}
        description={
          action === 'cancel'
            ? `Cancel ${t.tradeRef}. Positions are rebuilt without it. A SELL that depends on it blocks the cancel.`
            : `Mark ${t.tradeRef} settled now, ahead of the automatic T+1 settlement.`
        }
        actions={
          <>
            <Button variant="ghost" onClick={() => setAction(undefined)}>Back</Button>
            <Button
              variant={action === 'cancel' ? 'danger' : 'primary'}
              disabled={act.isPending}
              onClick={() => action && act.mutate({ kind: action, note: comment.trim() })}
            >
              {action === 'cancel' ? 'Cancel trade' : 'Settle'}
            </Button>
          </>
        }
      >
        <Field label="Comment (optional)" helper="Recorded in the trade's audit trail.">
          {(a) => <Textarea {...a} value={comment} maxLength={500} onChange={(e) => setComment(e.target.value)} />}
        </Field>
      </Dialog>
    </>
  )
}

function Item({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="border-b border-border py-3">
      <dt className="type-label">{label}</dt>
      <dd className="mt-1 text-[14px]">{children}</dd>
    </div>
  )
}
