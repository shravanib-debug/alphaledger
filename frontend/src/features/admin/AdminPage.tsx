import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'

import { analyticsApi } from '../../api/analytics'
import { authApi } from '../../api/auth'
import { toApiError } from '../../api/http'
import { notificationApi, opsApi } from '../../api/notification'
import type { AssetClass, Role } from '../../api/types'
import { marketDataApi, valuationApi } from '../../api/valuation'
import { ROLE_LABELS } from '../../auth/access'
import { Button } from '../../components/ui/Button'
import { ASSET_CLASS_LABELS } from '../../components/ui/chartTheme'
import { Dialog } from '../../components/ui/Dialog'
import { Field, Input, NumberInput } from '../../components/ui/Field'
import { PageHeader } from '../../components/ui/Layout'
import { Select } from '../../components/ui/Select'
import { EmptyState } from '../../components/ui/States'
import { TabPanel, Tabs } from '../../components/ui/Tabs'
import { StatusTag, Tag } from '../../components/ui/Tag'
import { useToast } from '../../components/ui/Toast'
import { DateText, Pct } from '../../components/ui/Values'
import { usePortfolioNames, usePortfolios } from '../../hooks/reference'
import { marketToday, timestamp } from '../../lib/format'

/** Admin-only tools (docs/ui-plan.md §6.10). */
export function AdminPage() {
  const [tab, setTab] = useState('jobs')
  return (
    <>
      <PageHeader eyebrow="Administration" title="Platform administration." description="Users, scheduled jobs, market data, limits and failed messages." />
      <div className="mt-6">
        <Tabs
          value={tab}
          onValueChange={setTab}
          tabs={[
            { value: 'jobs', label: 'Jobs' },
            { value: 'users', label: 'Users' },
            { value: 'limits', label: 'Limit rules' },
            { value: 'marketdata', label: 'Market data' },
            { value: 'deadletters', label: 'Dead letters' },
          ]}
        >
          <TabPanel value="jobs"><Jobs /></TabPanel>
          <TabPanel value="users"><Users /></TabPanel>
          <TabPanel value="limits"><Limits /></TabPanel>
          <TabPanel value="marketdata"><MarketData /></TabPanel>
          <TabPanel value="deadletters"><DeadLetters /></TabPanel>
        </Tabs>
      </div>
    </>
  )
}

function useErrorToast() {
  const toast = useToast()
  return (err: unknown) => {
    const e = toApiError(err)
    toast.error(e.message, e.correlationId)
  }
}

function Jobs() {
  const toast = useToast()
  const onError = useErrorToast()
  const queryClient = useQueryClient()
  const [date, setDate] = useState(marketToday())
  const [full, setFull] = useState(true)
  const [results, setResults] = useState<Record<string, string>>({})
  const note = (job: string, text: string) => setResults((r) => ({ ...r, [job]: text }))

  const eod = useMutation({ mutationFn: () => valuationApi.runEndOfDay(date), onSuccess: (r) => { note('eod', `${r.status} for ${r.valuationDate}`); toast.success('End-of-day valuation run.'); void queryClient.invalidateQueries() }, onError })
  const fees = useMutation({ mutationFn: () => valuationApi.accrueFees(date), onSuccess: (r) => { note('fees', `${r.portfoliosCharged} portfolios charged for ${r.accrualDate}`); void queryClient.invalidateQueries({ queryKey: ['liabilities'] }) }, onError })
  const etl = useMutation({
    mutationFn: () => analyticsApi.runEtl(date, full),
    onSuccess: (r) => {
      note('etl', `Run #${r.id}: ${r.status}${r.message ? `, ${r.message}` : ''}`)
      if (r.status === 'COMPLETED') toast.success(`ETL run #${r.id} completed.`)
      else toast.error(`ETL run #${r.id} ${r.status.toLowerCase()}: ${r.message ?? ''}`)
      void queryClient.invalidateQueries({ queryKey: ['etl-runs'] })
      void queryClient.invalidateQueries({ queryKey: ['dq-results'] })
    },
    onError,
  })
  const digest = useMutation({ mutationFn: notificationApi.digest, onSuccess: (r) => note('digest', r.created ? 'Digest created and queued for email' : 'No open breaks, nothing sent'), onError })

  return (
    <>
      <div className="mb-6 flex flex-wrap items-end gap-4 border-b border-border pb-5">
        <div className="w-[200px]">
          <Field label="Run date">{(a) => <Input {...a} type="date" className="tabular" value={date} max={marketToday()} onChange={(e) => setDate(e.target.value)} />}</Field>
        </div>
        <p className="type-meta pb-2">Each job runs on its schedule; these buttons run one now for the date above.</p>
      </div>
      <div className="divide-y divide-border border-b border-border">
        <JobRow title="End-of-day valuation" schedule="Business days, 23:45 IST" detail="Stores the day's NAV snapshot, checks limits and publishes events. A date that already ran is not run again." result={results.eod}>
          <Button disabled={eod.isPending} onClick={() => eod.mutate()}>Run now</Button>
        </JobRow>
        <JobRow title="Management fee accrual" schedule="Every day, 23:40 IST" detail="Accrues rate x market value / 365 as a liability, once per portfolio and day." result={results.fees}>
          <Button disabled={fees.isPending} onClick={() => fees.mutate()}>Run now</Button>
        </JobRow>
        <JobRow title="Analytics ETL" schedule="Every day, 02:00 IST" detail="Loads the warehouse and runs data-quality checks; a failed critical check fails the run." result={results.etl}>
          <label className="flex items-center gap-2 text-[13px]">
            <input type="checkbox" checked={full} onChange={(e) => setFull(e.target.checked)} className="accent-burgundy" /> Full refresh
          </label>
          <Button disabled={etl.isPending} onClick={() => etl.mutate()}>{etl.isPending ? 'Running' : 'Run now'}</Button>
        </JobRow>
        <JobRow title="Open-breaks digest" schedule="Business days, 09:00 IST" detail="Notifies Operations of every open break, by type and age." result={results.digest}>
          <Button disabled={digest.isPending} onClick={() => digest.mutate()}>Send now</Button>
        </JobRow>
      </div>
    </>
  )
}

function JobRow({ title, schedule, detail, result, children }: { title: string; schedule: string; detail: string; result?: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-1 gap-3 py-4 md:grid-cols-[1fr_auto] md:items-center">
      <div>
        <p className="text-[14px] font-medium">{title} <span className="type-meta font-normal">&nbsp; {schedule}</span></p>
        <p className="type-meta mt-0.5">{detail}</p>
        {result && <p className="mt-1.5 text-[13px] text-burgundy" role="status">{result}</p>}
      </div>
      <div className="flex items-center gap-3">{children}</div>
    </div>
  )
}

function Users() {
  const toast = useToast()
  const onError = useErrorToast()
  const queryClient = useQueryClient()
  const users = useQuery({ queryKey: ['users'], queryFn: authApi.users })
  const [open, setOpen] = useState(false)
  const [form, setForm] = useState({ email: '', fullName: '', password: '', role: 'OPS_ANALYST' as Role })
  const create = useMutation({
    mutationFn: () => authApi.createUser(form),
    onSuccess: (u) => {
      toast.success(`${u.fullName} can now sign in.`)
      setOpen(false)
      setForm({ email: '', fullName: '', password: '', role: 'OPS_ANALYST' })
      void queryClient.invalidateQueries({ queryKey: ['users'] })
      void queryClient.invalidateQueries({ queryKey: ['directory'] })
    },
    onError,
  })
  const valid = /.+@.+\..+/.test(form.email) && form.fullName.trim() && form.password.length >= 12

  return (
    <>
      <div className="mb-4 flex justify-end"><Button onClick={() => setOpen(true)}>New user</Button></div>
      <ul className="divide-y divide-border border-y border-border text-[13px]">
        {(users.data ?? []).map((u) => (
          <li key={u.id} className="grid grid-cols-[1fr_auto] gap-3 py-3 md:grid-cols-[1fr_1fr_180px_auto]">
            <span>{u.fullName}</span>
            <span className="type-meta hidden md:block">{u.email}</span>
            <span className="hidden md:block">{ROLE_LABELS[u.role]}</span>
            <StatusTag status={u.status} />
          </li>
        ))}
      </ul>
      <Dialog
        open={open}
        onOpenChange={setOpen}
        title="New user"
        actions={<><Button variant="ghost" onClick={() => setOpen(false)}>Cancel</Button><Button variant="primary" disabled={!valid || create.isPending} onClick={() => create.mutate()}>Create</Button></>}
      >
        <div className="space-y-4">
          <Field label="Full name">{(a) => <Input {...a} value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} />}</Field>
          <Field label="Email">{(a) => <Input {...a} type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />}</Field>
          <Field label="Initial password" helper="At least 12 characters.">{(a) => <Input {...a} type="password" autoComplete="new-password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />}</Field>
          <Field label="Role">
            {(a) => <Select {...a} value={form.role} onValueChange={(v) => setForm({ ...form, role: v as Role })} options={(Object.keys(ROLE_LABELS) as Role[]).map((r) => ({ value: r, label: ROLE_LABELS[r] }))} />}
          </Field>
        </div>
      </Dialog>
    </>
  )
}

function Limits() {
  const onError = useErrorToast()
  const queryClient = useQueryClient()
  const portfolios = usePortfolios()
  const portfolioName = usePortfolioNames()
  const rules = useQuery({ queryKey: ['limit-rules'], queryFn: valuationApi.limitRules })
  const [portfolioId, setPortfolioId] = useState('ALL')
  const [assetClass, setAssetClass] = useState<AssetClass>('REAL_ESTATE')
  const [maxPct, setMaxPct] = useState('')
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['limit-rules'] })
  const save = useMutation({
    mutationFn: () => valuationApi.saveLimitRule({ portfolioId: portfolioId === 'ALL' ? undefined : Number(portfolioId), assetClass, maxPct: Number(maxPct) }),
    onSuccess: () => { setMaxPct(''); refresh() },
    onError,
  })
  const remove = useMutation({ mutationFn: (id: number) => valuationApi.deleteLimitRule(id), onSuccess: refresh, onError })
  const pct = Number(maxPct)
  const valid = maxPct !== '' && pct > 0 && pct <= 100

  return (
    <>
      <div className="mb-6 grid grid-cols-1 items-end gap-4 border-b border-border pb-5 md:grid-cols-[1fr_1fr_140px_auto]">
        <Field label="Applies to">
          {(a) => <Select {...a} value={portfolioId} onValueChange={setPortfolioId} options={[{ value: 'ALL', label: 'Every portfolio' }, ...(portfolios.data ?? []).map((p) => ({ value: String(p.id), label: p.name }))]} />}
        </Field>
        <Field label="Asset class">
          {(a) => <Select {...a} value={assetClass} onValueChange={(v) => setAssetClass(v as AssetClass)} options={(['PRIVATE_EQUITY', 'CREDIT', 'REAL_ESTATE'] as AssetClass[]).map((c) => ({ value: c, label: ASSET_CLASS_LABELS[c]! }))} />}
        </Field>
        <Field label="Maximum share (%)">{(a) => <NumberInput {...a} value={maxPct} onChange={(e) => setMaxPct(e.target.value)} />}</Field>
        <Button variant="primary" disabled={!valid || save.isPending} onClick={() => save.mutate()}>Save rule</Button>
      </div>
      <p className="type-meta mb-3">A portfolio's own rule overrides the every-portfolio rule. Breaches are checked at the end-of-day valuation and notify portfolio managers.</p>
      {(rules.data ?? []).length === 0 ? <EmptyState message="No limit rules." /> : (
        <ul className="divide-y divide-border border-y border-border text-[13px]">
          {rules.data!.map((r) => (
            <li key={r.id} className="grid grid-cols-[1fr_1fr_100px_auto] items-center gap-3 py-2.5">
              <span>{r.portfolio_id === null ? 'Every portfolio' : portfolioName(r.portfolio_id)}</span>
              <span>{ASSET_CLASS_LABELS[r.asset_class]}</span>
              <span className="text-right"><Pct value={r.max_pct} /></span>
              <Button size="sm" variant="ghost" onClick={() => remove.mutate(r.id)}>Delete</Button>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}

function MarketData() {
  const ingestions = useQuery({ queryKey: ['ingestions'], queryFn: marketDataApi.ingestions })
  const prices = useQuery({ queryKey: ['latest-prices'], queryFn: () => marketDataApi.latest() })
  const stale = (prices.data ?? []).filter((p) => p.stale || p.price === null)
  return (
    <div className="grid grid-cols-1 gap-10 xl:grid-cols-2">
      <section>
        <h2 className="type-section mb-3 border-b border-border pb-2.5">Ingestion log</h2>
        <ul className="divide-y divide-border text-[13px]">
          {(ingestions.data ?? []).map((i) => (
            <li key={i.id} className="grid grid-cols-[1fr_auto] gap-2 py-2.5">
              <span>
                <span className="tabular">{i.source}</span> {i.data_date && <>, <DateText value={i.data_date} /></>}
                <span className="type-meta block truncate">{i.file_name ?? 'no file'} &nbsp; {i.rows_loaded ?? 0} loaded, {i.rows_rejected ?? 0} rejected &nbsp; {timestamp(i.started_at)}</span>
              </span>
              <Tag tone={i.status === 'SUCCESS' ? 'positive' : i.status === 'FAILED' ? 'negative' : 'outline'}>{i.status.replaceAll('_', ' ')}</Tag>
            </li>
          ))}
        </ul>
      </section>
      <section>
        <h2 className="type-section mb-3 border-b border-border pb-2.5">Stale or missing prices</h2>
        {stale.length === 0 ? <p className="type-meta py-4">Every instrument has a current price.</p> : (
          <ul className="divide-y divide-border text-[13px]">
            {stale.map((p) => (
              <li key={p.instrumentId} className="flex justify-between gap-3 py-2.5">
                <span>{p.name} <span className="type-meta tabular">{p.isin}</span></span>
                <span className="text-warn">{p.price === null ? 'no price' : <>last <DateText value={p.dataDate} /></>}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

function DeadLetters() {
  const toast = useToast()
  const onError = useErrorToast()
  const topics = useQuery({ queryKey: ['dlt-topics'], queryFn: opsApi.topics })
  const [topic, setTopic] = useState<string>()
  const [confirm, setConfirm] = useState(false)
  const selected = topic ?? topics.data?.[0]
  const base = selected?.replace(/\.DLT$/, '')
  const pending = useQuery({ queryKey: ['dlt', base], queryFn: () => opsApi.pending(base!), enabled: !!base })
  const replay = useMutation({
    mutationFn: () => opsApi.replay(base!),
    onSuccess: (r) => { toast.success(`${r.replayed} messages sent back to ${base}.`); setConfirm(false); void pending.refetch() },
    onError,
  })
  return (
    <>
      <p className="mb-4 text-[14px]">Messages a service could not process after its retries. Fix the cause, then replay them to their topic; consumers skip anything already processed.</p>
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <div className="w-[300px]">
          <Field label="Topic">{(a) => <Select {...a} value={selected} onValueChange={setTopic} options={(topics.data ?? []).map((t) => ({ value: t, label: t }))} />}</Field>
        </div>
        <Button disabled={!pending.data?.length} onClick={() => setConfirm(true)}>Replay {pending.data?.length ?? 0}</Button>
      </div>
      {(pending.data ?? []).length === 0 ? (
        <EmptyState message={pending.isLoading ? 'Loading' : 'Nothing waiting in this dead-letter topic.'} />
      ) : (
        <ul className="divide-y divide-border border-y border-border text-[13px]">
          {pending.data!.map((d) => (
            <li key={`${d.partition}-${d.offset}`} className="py-3">
              <p className="type-meta tabular">partition {d.partition}, offset {d.offset}, key {d.key ?? 'none'}</p>
              <p className="mt-1 text-loss">{d.exception ?? 'No error recorded'}</p>
              <code className="type-meta mt-1 block truncate">{d.value}</code>
            </li>
          ))}
        </ul>
      )}
      <Dialog
        open={confirm}
        onOpenChange={setConfirm}
        title={`Replay ${pending.data?.length ?? 0} messages`}
        description={`They are republished to ${base}. A message that fails again returns to the dead-letter topic.`}
        actions={<><Button variant="ghost" onClick={() => setConfirm(false)}>Cancel</Button><Button variant="primary" disabled={replay.isPending} onClick={() => replay.mutate()}>Replay</Button></>}
      />
    </>
  )
}
