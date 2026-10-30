import { ArrowLeft, Plus } from '@phosphor-icons/react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'

import { toApiError } from '../../api/http'
import { portfolioApi } from '../../api/portfolio'
import type { AssetClass, Portfolio } from '../../api/types'
import { valuationApi } from '../../api/valuation'
import { can } from '../../auth/access'
import { useAuth } from '../../auth/AuthProvider'
import { Button, ButtonLink } from '../../components/ui/Button'
import { ASSET_CLASS_LABELS } from '../../components/ui/chartTheme'
import { DataTable } from '../../components/ui/DataTable'
import { Dialog } from '../../components/ui/Dialog'
import { Field, Input } from '../../components/ui/Field'
import { Figure, FigureRow, PageHeader, Section } from '../../components/ui/Layout'
import { Select } from '../../components/ui/Select'
import { ErrorState, SkeletonRows } from '../../components/ui/States'
import { columnsFor } from '../../components/ui/tableColumns'
import { StatusTag } from '../../components/ui/Tag'
import { useToast } from '../../components/ui/Toast'
import { Pnl } from '../../components/ui/Values'
import { useDirectory, usePortfolios, useUserNames } from '../../hooks/reference'
import { compactRupees } from '../../lib/format'

const ASSET_CLASSES: AssetClass[] = ['PRIVATE_EQUITY', 'CREDIT', 'REAL_ESTATE']

export function PortfoliosPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const queryClient = useQueryClient()
  const portfolios = usePortfolios()
  const userName = useUserNames()
  const directory = useDirectory()
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [managerId, setManagerId] = useState<string>()

  const create = useMutation({
    mutationFn: () => portfolioApi.create({ name: name.trim(), managerId: user?.role === 'ADMIN' ? Number(managerId) : user?.id }),
    onSuccess: (p) => {
      toast.success(`Portfolio ${p.name} created.`)
      setCreating(false)
      setName('')
      void queryClient.invalidateQueries({ queryKey: ['portfolios'] })
      navigate(`/portfolios/${p.id}`)
    },
    onError: (err) => {
      const e = toApiError(err)
      toast.error(e.message, e.correlationId)
    },
  })

  const col = columnsFor<Portfolio>()
  const columns = [
    col.accessor('name', { header: 'Portfolio' }),
    col.accessor('managerId', { header: 'Manager', meta: { muted: true }, cell: (c) => userName(c.getValue()) }),
    col.accessor('funds', { header: 'Funds', cell: (c) => c.getValue().map((f) => f.name).join(', ') || <span className="text-grey">none</span> }),
    col.accessor('baseCurrency', { header: 'Currency', meta: { muted: true } }),
    col.accessor('status', { header: 'Status', cell: (c) => <StatusTag status={c.getValue()} /> }),
  ]
  const error = portfolios.error ? toApiError(portfolios.error) : undefined
  const managers = (directory.data ?? []).filter((u) => u.active && u.role === 'PM')

  return (
    <>
      <PageHeader
        eyebrow="Portfolios"
        title="Portfolios and funds."
        description="Each portfolio is split into funds by asset class. Portfolio managers change only the portfolios they manage."
        aside={can(user?.role, 'portfolio.manage') && <Button variant="primary" onClick={() => setCreating(true)}><Plus size={14} /> New portfolio</Button>}
      />
      <div className="mt-6">
        <DataTable
          caption="Portfolios"
          columns={columns}
          data={portfolios.data}
          loading={portfolios.isLoading}
          error={error && { message: error.message, correlationId: error.correlationId }}
          onRetry={() => portfolios.refetch()}
          empty="No portfolios yet."
          onRowClick={(p) => navigate(`/portfolios/${p.id}`)}
        />
      </div>
      <Dialog
        open={creating}
        onOpenChange={setCreating}
        title="New portfolio"
        actions={
          <>
            <Button variant="ghost" onClick={() => setCreating(false)}>Cancel</Button>
            <Button variant="primary" disabled={!name.trim() || (user?.role === 'ADMIN' && !managerId) || create.isPending} onClick={() => create.mutate()}>Create</Button>
          </>
        }
      >
        <div className="space-y-4">
          <Field label="Name">{(a) => <Input {...a} maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
          {user?.role === 'ADMIN' && (
            <Field label="Portfolio manager">
              {(a) => <Select {...a} value={managerId} onValueChange={setManagerId} placeholder="Choose a manager" options={managers.map((m) => ({ value: String(m.id), label: m.fullName }))} />}
            </Field>
          )}
        </div>
      </Dialog>
    </>
  )
}

export function PortfolioDetailPage() {
  const id = Number(useParams().id)
  const { user } = useAuth()
  const toast = useToast()
  const queryClient = useQueryClient()
  const userName = useUserNames()
  const [fundName, setFundName] = useState('')
  const [assetClass, setAssetClass] = useState<AssetClass>('PRIVATE_EQUITY')
  const [adding, setAdding] = useState(false)

  const portfolio = useQuery({ queryKey: ['portfolio', id], queryFn: () => portfolioApi.get(id) })
  const valuation = useQuery({ queryKey: ['valuation', id, 'live'], queryFn: () => valuationApi.valuation(id), refetchInterval: 15_000 })
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['portfolio', id] })
    void queryClient.invalidateQueries({ queryKey: ['portfolios'] })
  }
  const onError = (err: unknown) => {
    const e = toApiError(err)
    toast.error(e.message, e.correlationId)
  }
  const addFund = useMutation({
    mutationFn: () => portfolioApi.addFund(id, { name: fundName.trim(), assetClass }),
    onSuccess: () => {
      toast.success('Fund added.')
      setAdding(false)
      setFundName('')
      refresh()
    },
    onError,
  })
  const archive = useMutation({ mutationFn: () => portfolioApi.archive(id), onSuccess: () => { toast.success('Portfolio archived.'); refresh() }, onError })

  if (portfolio.error) {
    const e = toApiError(portfolio.error)
    return <ErrorState message={e.status === 404 ? `Portfolio ${id} not found.` : e.message} correlationId={e.correlationId} />
  }
  if (!portfolio.data) {
    return <SkeletonRows rows={5} columns={3} />
  }
  const p = portfolio.data
  const v = valuation.data
  const manages = can(user?.role, 'portfolio.manage') && (user?.role === 'ADMIN' || p.managerId === user?.id) && p.status === 'ACTIVE'

  return (
    <>
      <Link to="/portfolios" className="mb-4 inline-flex items-center gap-1.5 text-[13px] text-grey hover:text-espresso">
        <ArrowLeft size={13} /> Portfolios
      </Link>
      <PageHeader
        eyebrow="Portfolio"
        title={`${p.name}.`}
        description={`Managed by ${userName(p.managerId)}, base currency ${p.baseCurrency}.`}
        aside={
          <div className="flex flex-wrap items-center gap-2">
            <StatusTag status={p.status} />
            <ButtonLink to={`/positions?portfolio=${p.id}`}>Positions</ButtonLink>
            <ButtonLink to={`/trades?portfolio=${p.id}`}>Trades</ButtonLink>
            {manages && <Button variant="danger" disabled={archive.isPending} onClick={() => archive.mutate()}>Archive</Button>}
          </div>
        }
      />
      <FigureRow>
        <Figure label="NAV" value={v?.nav != null ? compactRupees(v.nav) : '-'} />
        <Figure label="Market value" value={v ? compactRupees(v.marketValue) : '-'} />
        <Figure label="Cash" value={v ? compactRupees(v.cashBalance) : '-'} />
        <Figure label="Unrealized P&L" value={v ? <Pnl value={v.details.unrealizedPnl} /> : '-'} />
      </FigureRow>
      <Section title="Funds" actions={manages && <Button size="sm" onClick={() => setAdding(true)}>Add fund</Button>}>
        {p.funds.length === 0 ? (
          <p className="type-meta py-4">No funds yet.</p>
        ) : (
          <ul className="divide-y divide-border text-[14px]">
            {p.funds.map((f) => (
              <li key={f.id} className="flex justify-between py-3">
                <span>{f.name}</span>
                <span className="type-meta">{ASSET_CLASS_LABELS[f.assetClass]}</span>
              </li>
            ))}
          </ul>
        )}
      </Section>
      <Dialog
        open={adding}
        onOpenChange={setAdding}
        title="Add fund"
        actions={
          <>
            <Button variant="ghost" onClick={() => setAdding(false)}>Cancel</Button>
            <Button variant="primary" disabled={!fundName.trim() || addFund.isPending} onClick={() => addFund.mutate()}>Add</Button>
          </>
        }
      >
        <div className="space-y-4">
          <Field label="Name">{(a) => <Input {...a} maxLength={120} value={fundName} onChange={(e) => setFundName(e.target.value)} />}</Field>
          <Field label="Asset class">
            {(a) => <Select {...a} value={assetClass} onValueChange={(v) => setAssetClass(v as AssetClass)} options={ASSET_CLASSES.map((c) => ({ value: c, label: ASSET_CLASS_LABELS[c]! }))} />}
          </Field>
        </div>
      </Dialog>
    </>
  )
}
