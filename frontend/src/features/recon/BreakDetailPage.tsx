import { ArrowLeft } from '@phosphor-icons/react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'

import { toApiError } from '../../api/http'
import { reconApi } from '../../api/recon'
import type { ReconBreak, Resolution } from '../../api/types'
import { can } from '../../auth/access'
import { useAuth } from '../../auth/AuthProvider'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { Field, Textarea } from '../../components/ui/Field'
import { PageHeader, Section } from '../../components/ui/Layout'
import { Select } from '../../components/ui/Select'
import { ErrorState, SkeletonRows } from '../../components/ui/States'
import { StatusTag, Tag } from '../../components/ui/Tag'
import { useToast } from '../../components/ui/Toast'
import { DateText, Price, Quantity } from '../../components/ui/Values'
import { useDirectory, usePortfolioNames, useUserNames } from '../../hooks/reference'
import { cn } from '../../lib/cn'
import { timestamp } from '../../lib/format'
import { BREAK_TYPE_LABELS, RESOLUTION_LABELS } from './breakLabels'

type Action = 'assign' | 'escalate' | 'resolve'

/** Internal versus custodian side by side; assign, escalate, resolve with a comment; the audit trail. */
export function BreakDetailPage() {
  const id = Number(useParams().id)
  const { user } = useAuth()
  const toast = useToast()
  const queryClient = useQueryClient()
  const portfolioName = usePortfolioNames()
  const userName = useUserNames()
  const directory = useDirectory()
  const [action, setAction] = useState<Action>()
  const [comment, setComment] = useState('')
  const [assignee, setAssignee] = useState<string>()
  const [resolution, setResolution] = useState<Resolution>('ADJUST_INTERNAL')

  const brk = useQuery({ queryKey: ['break', id], queryFn: () => reconApi.break(id) })
  const audit = useQuery({ queryKey: ['break-audit', id], queryFn: () => reconApi.audit(id) })

  const act = useMutation({
    mutationFn: (): Promise<ReconBreak> => {
      const note = comment.trim()
      if (action === 'assign') {
        return reconApi.assign(id, note, assignee ? Number(assignee) : undefined)
      }
      if (action === 'escalate') {
        return reconApi.escalate(id, note)
      }
      return reconApi.resolve(id, resolution, note)
    },
    onSuccess: (b) => {
      toast.success(b.status === 'RESOLVED' ? 'Break resolved.' : b.status === 'ESCALATED' ? 'Break escalated.' : `Assigned to ${userName(b.assigned_to)}.`)
      setAction(undefined)
      setComment('')
      void queryClient.invalidateQueries({ queryKey: ['break', id] })
      void queryClient.invalidateQueries({ queryKey: ['break-audit', id] })
      void queryClient.invalidateQueries({ queryKey: ['breaks'] })
    },
    onError: (err) => {
      const e = toApiError(err)
      toast.error(e.message, e.correlationId)
    },
  })

  if (brk.error) {
    const e = toApiError(brk.error)
    return <ErrorState message={e.status === 404 ? `Break ${id} not found.` : e.message} correlationId={e.correlationId} onRetry={() => brk.refetch()} />
  }
  if (!brk.data) {
    return <SkeletonRows rows={6} columns={3} />
  }

  const b = brk.data
  const works = can(user?.role, 'break.work')
  const canAssign = works && (b.status === 'OPEN' || b.status === 'IN_REVIEW')
  const canEscalate = works && b.status === 'IN_REVIEW'
  const canResolve = works && (b.status === 'IN_REVIEW' || b.status === 'ESCALATED')
  const qtyDiffers = b.internal_qty !== b.external_qty
  const priceDiffers = b.internal_price !== b.external_price
  const analysts = (directory.data ?? []).filter((u) => u.active && (u.role === 'OPS_ANALYST' || u.role === 'ADMIN'))

  return (
    <>
      <Link to="/recon/breaks" className="mb-4 inline-flex items-center gap-1.5 text-[13px] text-grey hover:text-espresso">
        <ArrowLeft size={13} /> Breaks
      </Link>
      <PageHeader
        eyebrow={`Break ${b.id}, run #${b.run_id}`}
        title={`${BREAK_TYPE_LABELS[b.break_type]} on ${b.trade_ref}.`}
        description={`${b.portfolio_id ? portfolioName(b.portfolio_id) : 'No internal trade'}, ISIN ${b.isin}, raised ${timestamp(b.created_at)}.`}
        aside={
          <div className="flex flex-wrap items-center gap-2">
            <StatusTag status={b.status} />
            {b.overdue && <Tag tone="warn">past SLA</Tag>}
          </div>
        }
      />

      <div className="mt-8 grid grid-cols-1 border-y border-border md:grid-cols-[180px_1fr_1fr]">
        <div className="hidden md:block" />
        <p className="type-label border-b border-border py-3 md:px-6">Internal (our books)</p>
        <p className="type-label border-b border-border py-3 md:px-6">Custodian file</p>
        <Row label="Quantity" differs={qtyDiffers} internal={b.internal_qty === null ? null : <Quantity value={b.internal_qty} />} external={b.external_qty === null ? null : <Quantity value={b.external_qty} />} />
        <Row label="Price (₹)" differs={priceDiffers} internal={b.internal_price === null ? null : <Price value={b.internal_price} />} external={b.external_price === null ? null : <Price value={b.external_price} />} />
        <Row label="Trade date" differs={false} internal={<DateText value={b.trade_date} />} external={<DateText value={b.trade_date} />} />
      </div>

      <dl className="mt-6 grid grid-cols-2 gap-x-10 md:grid-cols-4">
        <Meta label="Age">{b.age_days} days</Meta>
        <Meta label="Assignee">{b.assigned_to ? userName(b.assigned_to) : 'Unassigned'}</Meta>
        <Meta label="Resolution">{b.resolution_type ? RESOLUTION_LABELS[b.resolution_type] : '-'}</Meta>
        <Meta label="Resolved">{b.resolved_at ? timestamp(b.resolved_at) : '-'}</Meta>
      </dl>

      {works && b.status !== 'RESOLVED' && (
        <div className="mt-6 flex flex-wrap gap-2">
          {canAssign && <Button onClick={() => { setAssignee(undefined); setAction('assign') }}>{b.status === 'OPEN' ? 'Assign' : 'Reassign'}</Button>}
          {canEscalate && <Button onClick={() => setAction('escalate')}>Escalate</Button>}
          {canResolve && <Button variant="primary" onClick={() => setAction('resolve')}>Resolve</Button>}
          {b.status === 'OPEN' && <p className="type-meta self-center">Assign the break to start working it; it can then be escalated or resolved.</p>}
        </div>
      )}

      <Section title="Audit trail">
        {audit.isLoading ? (
          <SkeletonRows rows={3} columns={3} />
        ) : (audit.data ?? []).length === 0 ? (
          <p className="type-meta py-4">No actions yet.</p>
        ) : (
          <ol className="relative ml-1.5 border-l border-border">
            {audit.data!.map((a) => (
              <li key={a.id} className="relative pb-5 pl-6 last:pb-0">
                <span className="absolute -left-[4.5px] top-1.5 h-2 w-2 rounded-full bg-taupe" aria-hidden />
                <p className="text-[13px]">
                  <span className="font-medium">{a.action.replaceAll('_', ' ').toLowerCase()}</span>
                  <span className="type-meta"> &nbsp; {a.performed_by ? userName(a.performed_by) : 'system'}, {timestamp(a.performed_at)}</span>
                </p>
                <p className="mt-1 text-[13px]">{a.comment}</p>
              </li>
            ))}
          </ol>
        )}
      </Section>

      <Dialog
        open={action !== undefined}
        onOpenChange={(o) => !o && setAction(undefined)}
        title={action === 'assign' ? 'Assign break' : action === 'escalate' ? 'Escalate break' : 'Resolve break'}
        description="A comment is required and is recorded in the audit trail."
        actions={
          <>
            <Button variant="ghost" onClick={() => setAction(undefined)}>Cancel</Button>
            <Button variant="primary" disabled={!comment.trim() || act.isPending} onClick={() => act.mutate()}>
              {action === 'assign' ? 'Assign' : action === 'escalate' ? 'Escalate' : 'Resolve'}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          {action === 'assign' && (
            <Field label="Assign to">
              {(a) => (
                <Select {...a} value={assignee ?? ''} onValueChange={setAssignee} placeholder="Me" options={analysts.map((u) => ({ value: String(u.id), label: u.id === user?.id ? `${u.fullName} (me)` : u.fullName }))} />
              )}
            </Field>
          )}
          {action === 'resolve' && (
            <Field label="Resolution">
              {(a) => (
                <Select {...a} value={resolution} onValueChange={(v) => setResolution(v as Resolution)} options={Object.entries(RESOLUTION_LABELS).map(([value, label]) => ({ value, label }))} />
              )}
            </Field>
          )}
          <Field label="Comment">
            {(a) => <Textarea {...a} value={comment} maxLength={500} onChange={(e) => setComment(e.target.value)} />}
          </Field>
        </div>
      </Dialog>
    </>
  )
}

function Row({ label, internal, external, differs }: { label: string; internal: ReactNode; external: ReactNode; differs: boolean }) {
  const cell = (v: ReactNode) => (
    <div className={cn('border-b border-border py-3 text-[15px] tabular md:px-6', differs && 'text-loss')}>
      {v ?? <span className="text-grey">not present</span>}
    </div>
  )
  return (
    <>
      <div className="type-meta border-b border-border py-3 md:border-r">
        {label}
        {differs && <span className="sr-only"> (differs)</span>}
      </div>
      {cell(internal)}
      {cell(external)}
    </>
  )
}

function Meta({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="border-b border-border py-3">
      <dt className="type-label">{label}</dt>
      <dd className="mt-1 text-[14px]">{children}</dd>
    </div>
  )
}
