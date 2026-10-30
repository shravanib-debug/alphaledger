import { ArrowRight, UploadSimple } from '@phosphor-icons/react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'

import { toApiError } from '../../api/http'
import { reconApi } from '../../api/recon'
import type { ReconRun } from '../../api/types'
import { can } from '../../auth/access'
import { useAuth } from '../../auth/AuthProvider'
import { Button } from '../../components/ui/Button'
import { DataTable } from '../../components/ui/DataTable'
import { Field, Input } from '../../components/ui/Field'
import { Figure, FigureRow, PageHeader, Section } from '../../components/ui/Layout'
import { EmptyState } from '../../components/ui/States'
import { columnsFor } from '../../components/ui/tableColumns'
import { StatusTag } from '../../components/ui/Tag'
import { useToast } from '../../components/ui/Toast'
import { DateText, Pct } from '../../components/ui/Values'
import { marketToday, timestamp } from '../../lib/format'

const col = columnsFor<ReconRun>()
const RUN_COLUMNS = [
  col.accessor('id', { header: 'Run', cell: (c) => <span className="tabular">#{c.getValue()}</span> }),
  col.accessor('run_date', { header: 'Settlement date', cell: (c) => <DateText value={c.getValue()} /> }),
  col.accessor('file_name', { header: 'File', meta: { muted: true }, cell: (c) => c.getValue() ?? '' }),
  col.accessor('total_records', { header: 'Records', meta: { align: 'right' } }),
  col.accessor('matched_count', { header: 'Matched', meta: { align: 'right' } }),
  col.accessor('break_count', { header: 'Breaks', meta: { align: 'right' } }),
  col.accessor('status', { header: 'Status', cell: (c) => <StatusTag status={c.getValue()} /> }),
  col.accessor('started_at', { header: 'Started', meta: { muted: true }, cell: (c) => timestamp(c.getValue()) }),
]

/** Custodian file upload, the selected run's summary, and run history (docs/ui-plan.md §6.7). */
export function ReconPage() {
  const { user } = useAuth()
  const toast = useToast()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [params, setParams] = useSearchParams()
  const [file, setFile] = useState<File>()
  const [runDate, setRunDate] = useState(marketToday())

  const runs = useQuery({ queryKey: ['recon-runs'], queryFn: reconApi.runs, refetchInterval: (q) => (q.state.data?.some((r) => r.status === 'RUNNING') ? 2000 : false) })
  const selectedId = Number(params.get('run')) || runs.data?.[0]?.id
  const run = useQuery({ queryKey: ['recon-run', selectedId], queryFn: () => reconApi.run(selectedId!), enabled: !!selectedId })
  const errors = useQuery({ queryKey: ['recon-run-errors', selectedId], queryFn: () => reconApi.runErrors(selectedId!), enabled: !!selectedId && (run.data?.error_rows ?? 0) > 0 })

  const upload = useMutation({
    mutationFn: () => reconApi.upload(file!, runDate),
    onSuccess: ({ run: r, duplicate }) => {
      if (duplicate) {
        toast.info(`This file was already reconciled for that date: run #${r.id}.`)
      } else {
        toast.success(`Run #${r.id} reconciled: ${r.matched_count} matched, ${r.break_count} breaks.`)
      }
      setFile(undefined)
      setParams({ run: String(r.id) })
      void queryClient.invalidateQueries({ queryKey: ['recon-runs'] })
      void queryClient.invalidateQueries({ queryKey: ['breaks'] })
    },
    onError: (err) => {
      const e = toApiError(err)
      toast.error(e.message, e.correlationId)
    },
  })

  const r = run.data
  return (
    <>
      <PageHeader
        eyebrow="Reconciliation"
        title="Custodian reconciliation."
        description="Settled trades matched to the custodian's file on trade reference, ISIN and trade date."
        aside={<Link to="/recon/breaks" className="inline-flex items-center gap-1.5 text-[13px] text-burgundy">Work the breaks <ArrowRight size={13} /></Link>}
      />

      {can(user?.role, 'recon.upload') && (
        <section className="mt-6 flex flex-wrap items-end gap-4 border-b border-border pb-6">
          <div className="w-[220px]">
            <Field label="Settlement date covered">
              {(a) => <Input {...a} type="date" className="tabular" value={runDate} max={marketToday()} onChange={(e) => setRunDate(e.target.value)} />}
            </Field>
          </div>
          <label className="inline-flex h-8 cursor-pointer items-center gap-2 rounded-[var(--radius-control)] border border-dashed border-taupe px-3.5 text-[13px] hover:border-burgundy">
            <UploadSimple size={14} /> {file ? file.name : 'Choose custodian CSV'}
            <input type="file" accept=".csv,text/csv" className="sr-only" onChange={(e) => setFile(e.target.files?.[0])} />
          </label>
          <Button variant="primary" disabled={!file || !runDate || upload.isPending} onClick={() => upload.mutate()}>
            {upload.isPending ? 'Reconciling' : 'Upload and reconcile'}
          </Button>
          <p className="type-meta basis-full">Header: trade_ref, isin, trade_date, settle_date, side, quantity, price. Uploading the same file again returns the same run.</p>
        </section>
      )}

      {!r ? (
        runs.data && runs.data.length === 0 ? <EmptyState message="No reconciliation runs yet. Upload a custodian file to start." /> : null
      ) : (
        <Section title={`Run #${r.id}, ${r.source}`} actions={<StatusTag status={r.status} />}>
          {r.error_message && <p className="mb-3 border-l-2 border-loss pl-4 text-[13px] text-loss">{r.error_message}</p>}
          <FigureRow>
            <Figure label="Records" value={r.total_records} detail={`${r.error_rows} rejected rows`} />
            <Figure label="Matched" value={r.matched_count} detail={<span>Match rate <Pct value={r.match_rate_pct ?? null} /></span>} />
            <Figure label="Breaks" value={<span className="text-burgundy">{r.break_count}</span>} />
            <Figure label="Missing" value={`${r.missing_internal} / ${r.missing_external}`} detail="internal / at custodian" />
            <Figure label="Mismatches" value={`${r.qty_mismatch} / ${r.price_mismatch}`} detail="quantity / price" />
          </FigureRow>
          <div className="flex flex-wrap gap-2">
            <Link to={`/recon/breaks?runId=${r.id}`} className="text-[13px] text-burgundy">Breaks from this run</Link>
          </div>
          {(errors.data ?? []).length > 0 && (
            <div className="mt-5">
              <p className="type-label">Rejected rows</p>
              <ul className="mt-2 divide-y divide-border text-[13px]">
                {errors.data!.map((e) => (
                  <li key={e.row_number} className="grid grid-cols-[80px_1fr] gap-3 py-2">
                    <span className="type-meta tabular">Row {e.row_number}</span>
                    <span>
                      <span className="text-loss">{e.error_message}</span>
                      <code className="type-meta mt-0.5 block truncate">{e.raw_row}</code>
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </Section>
      )}

      <Section title="Run history">
        <DataTable
          caption="Reconciliation runs"
          columns={RUN_COLUMNS}
          data={runs.data}
          loading={runs.isLoading}
          empty="No runs yet."
          onRowClick={(row) => {
            setParams({ run: String(row.id) })
            navigate(`/recon?run=${row.id}`, { replace: true })
          }}
        />
      </Section>
    </>
  )
}
