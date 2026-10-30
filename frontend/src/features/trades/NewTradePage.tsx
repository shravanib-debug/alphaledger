import { zodResolver } from '@hookform/resolvers/zod'
import { UploadSimple } from '@phosphor-icons/react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router'
import { z } from 'zod'

import { toApiError } from '../../api/http'
import { tradeApi } from '../../api/trade'
import type { BulkUploadResult, Portfolio, Trade } from '../../api/types'
import { marketDataApi } from '../../api/valuation'
import { useAuth } from '../../auth/AuthProvider'
import { Button, ButtonLink } from '../../components/ui/Button'
import { Field, Input, NumberInput } from '../../components/ui/Field'
import { PageHeader, Section } from '../../components/ui/Layout'
import { Select } from '../../components/ui/Select'
import { SourceBadge } from '../../components/ui/SourceBadge'
import { ErrorState, SkeletonRows } from '../../components/ui/States'
import { TabPanel, Tabs } from '../../components/ui/Tabs'
import { useToast } from '../../components/ui/Toast'
import { Money, Price } from '../../components/ui/Values'
import { useInstruments, usePortfolios } from '../../hooks/reference'
import { addBusinessDays, isBusinessDay } from '../../lib/calendar'
import { calendarDate, marketToday } from '../../lib/format'

/** Mirrors CreateTradeRequest's validation in trade-service. */
const schema = z.object({
  portfolioId: z.string().min(1, 'Choose a portfolio'),
  isin: z.string().length(12, 'Choose an instrument'),
  side: z.enum(['BUY', 'SELL']),
  quantity: z.string().regex(/^\d+(\.\d{1,4})?$/, 'Up to 4 decimals').refine((v) => Number(v) > 0, 'Must be greater than 0'),
  price: z.string().regex(/^\d+(\.\d{1,6})?$/, 'Up to 6 decimals').refine((v) => Number(v) > 0, 'Must be greater than 0'),
  tradeDate: z.string().min(1, 'Required'),
  tradeRef: z.string().max(40).regex(/^[A-Za-z0-9._-]*$/, "Letters, digits, '.', '_' and '-' only").optional(),
})
type FormValues = z.infer<typeof schema>

export function NewTradePage() {
  const [tab, setTab] = useState('single')
  return (
    <>
      <PageHeader eyebrow="Trades" title="New trade." description="Captured trades are validated at once; a rejected trade is kept with its reason." />
      <div className="mt-6">
        <Tabs value={tab} onValueChange={setTab} tabs={[{ value: 'single', label: 'Single trade' }, { value: 'upload', label: 'CSV upload' }]}>
          <TabPanel value="single">
            <TradeForm />
          </TabPanel>
          <TabPanel value="upload">
            <TradeUpload />
          </TabPanel>
        </Tabs>
      </div>
    </>
  )
}

/** Waits for the portfolio list so the form can start with a bookable portfolio selected. */
function TradeForm() {
  const { user } = useAuth()
  const portfolios = usePortfolios()
  if (portfolios.error) {
    const e = toApiError(portfolios.error)
    return <ErrorState message={e.message} correlationId={e.correlationId} onRetry={() => portfolios.refetch()} />
  }
  if (!portfolios.data) {
    return <SkeletonRows rows={4} columns={2} />
  }
  // A PM books only into portfolios they manage; an admin into any active one.
  const bookable = portfolios.data.filter((p) => p.status === 'ACTIVE' && (user?.role === 'ADMIN' || p.managerId === user?.id))
  if (bookable.length === 0) {
    return <ErrorState message="You do not manage any active portfolio, so there is nowhere to book a trade." />
  }
  return <TradeFormFields bookable={bookable} />
}

function TradeFormFields({ bookable }: { bookable: Portfolio[] }) {
  const navigate = useNavigate()
  const toast = useToast()
  const queryClient = useQueryClient()
  const instruments = useInstruments()
  const [rejected, setRejected] = useState<Trade>()

  const { control, register, handleSubmit, watch, setError, formState } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { portfolioId: bookable[0] ? String(bookable[0].id) : '', isin: '', side: 'BUY', quantity: '', price: '', tradeDate: marketToday(), tradeRef: '' },
  })
  const [isin, side, quantity, price, tradeDate] = watch(['isin', 'side', 'quantity', 'price', 'tradeDate'])

  const latest = useQuery({ queryKey: ['latest-price', isin], queryFn: () => marketDataApi.latest(isin), enabled: isin.length === 12 })
  const year = Number(tradeDate.slice(0, 4)) || new Date().getFullYear()
  const holidays = useQuery({ queryKey: ['holidays', year], queryFn: () => marketDataApi.holidays(year), staleTime: Infinity })
  const holidaySet = useMemo(() => new Set((holidays.data ?? []).map((h) => h.holiday_date)), [holidays.data])
  const tradeDayOk = !tradeDate || isBusinessDay(tradeDate, holidaySet)
  const settlePreview = tradeDate && tradeDayOk ? addBusinessDays(tradeDate, 1, holidaySet) : undefined
  const notional = Number(quantity) > 0 && Number(price) > 0 ? Number(quantity) * Number(price) : null
  const price0 = latest.data?.[0]

  const create = useMutation({
    mutationFn: (v: FormValues) =>
      tradeApi.create({
        portfolioId: Number(v.portfolioId),
        isin: v.isin,
        side: v.side,
        quantity: Number(v.quantity),
        price: Number(v.price),
        tradeDate: v.tradeDate,
        tradeRef: v.tradeRef || undefined,
      }),
    onSuccess: (trade) => {
      void queryClient.invalidateQueries({ queryKey: ['trades'] })
      if (trade.status === 'REJECTED') {
        setRejected(trade)
        return
      }
      toast.success(`Trade ${trade.tradeRef} booked. Positions and valuation update within a few seconds.`)
      navigate(`/trades/${trade.id}`)
    },
    onError: (err) => {
      const e = toApiError(err)
      const fields = Object.entries(e.fieldErrors)
      fields.forEach(([name, message]) => {
        if (name in schema.shape) {
          setError(name as keyof FormValues, { message })
        }
      })
      if (fields.length === 0) {
        toast.error(e.message, e.correlationId)
      }
    },
  })

  return (
    <form onSubmit={handleSubmit((v) => create.mutate(v))} noValidate className="grid grid-cols-1 gap-10 lg:grid-cols-[minmax(0,560px)_1fr]">
      <div className="space-y-4">
        {rejected && (
          <div role="alert" className="border-l-2 border-loss py-2 pl-4">
            <p className="text-[14px]">
              Trade {rejected.tradeRef} was <strong className="font-medium text-loss">rejected</strong>: {rejected.rejectionReason}
            </p>
            <p className="type-meta mt-1">
              It is kept in the blotter for the audit trail. <Link to={`/trades/${rejected.id}`} className="text-burgundy">View it</Link>
            </p>
          </div>
        )}
        <Field label="Portfolio" error={formState.errors.portfolioId?.message}>
          {(a) => (
            <Controller control={control} name="portfolioId" render={({ field }) => (
              <Select {...a} value={field.value || undefined} onValueChange={field.onChange} placeholder="Choose a portfolio" options={bookable.map((p) => ({ value: String(p.id), label: p.name }))} />
            )} />
          )}
        </Field>
        <Field label="Instrument" error={formState.errors.isin?.message}>
          {(a) => (
            <Controller control={control} name="isin" render={({ field }) => (
              <Select
                {...a}
                value={field.value || undefined}
                onValueChange={field.onChange}
                placeholder={instruments.isLoading ? 'Loading instruments' : 'Choose an instrument'}
                options={(instruments.data ?? []).filter((i) => i.status === 'ACTIVE').map((i) => ({ value: i.isin, label: `${i.name}  (${i.isin})` }))}
              />
            )} />
          )}
        </Field>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <Field label="Side" error={formState.errors.side?.message}>
            {(a) => (
              <Controller control={control} name="side" render={({ field }) => (
                <Select {...a} value={field.value} onValueChange={field.onChange} options={[{ value: 'BUY', label: 'Buy' }, { value: 'SELL', label: 'Sell' }]} />
              )} />
            )}
          </Field>
          <Field label="Quantity" error={formState.errors.quantity?.message}>
            {(a) => <NumberInput {...a} {...register('quantity')} />}
          </Field>
          <Field label="Price (₹)" error={formState.errors.price?.message}>
            {(a) => <NumberInput {...a} {...register('price')} />}
          </Field>
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field
            label="Trade date"
            error={formState.errors.tradeDate?.message ?? (!tradeDayOk ? 'Not an NSE business day (weekend or trading holiday).' : undefined)}
            helper={settlePreview ? `Settles ${calendarDate(settlePreview)} (T+1 business day).` : undefined}
          >
            {(a) => <Input {...a} type="date" max={marketToday()} className="tabular" {...register('tradeDate')} />}
          </Field>
          <Field label="Trade reference (optional)" error={formState.errors.tradeRef?.message} helper="Generated if left blank.">
            {(a) => <Input {...a} className="tabular" {...register('tradeRef')} />}
          </Field>
        </div>
        <div className="flex gap-2 border-t border-border pt-5">
          <Button type="submit" variant="primary" disabled={create.isPending || !tradeDayOk}>
            {create.isPending ? 'Booking' : `Book ${side === 'SELL' ? 'sell' : 'buy'}`}
          </Button>
          <ButtonLink to="/trades" variant="ghost">Cancel</ButtonLink>
        </div>
      </div>

      <aside className="space-y-6 lg:border-l lg:border-border lg:pl-10">
        <div>
          <p className="type-label">Latest price</p>
          {!isin ? (
            <p className="type-meta mt-2">Choose an instrument to see its latest price.</p>
          ) : price0?.price != null && price0.source && price0.dataDate ? (
            <div className="mt-2 flex flex-wrap items-center gap-3">
              <span className="type-figure"><Price value={price0.price} /></span>
              <SourceBadge source={price0.source} dataDate={price0.dataDate} stale={price0.stale} fairValueLevel={price0.fairValueLevel} />
            </div>
          ) : latest.isLoading ? (
            <p className="type-meta mt-2">Loading</p>
          ) : (
            <p className="type-meta mt-2">No price on record for this instrument.</p>
          )}
        </div>
        <div>
          <p className="type-label">Notional</p>
          <p className="type-figure mt-2">{notional === null ? <span className="text-grey">-</span> : <Money value={Math.round(notional * 100) / 100} />}</p>
          <p className="type-meta mt-1">Quantity x price, in INR.</p>
        </div>
      </aside>
    </form>
  )
}

function TradeUpload() {
  const toast = useToast()
  const queryClient = useQueryClient()
  const [file, setFile] = useState<File>()
  const upload = useMutation({
    mutationFn: (f: File) => tradeApi.upload(f),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['trades'] }),
    onError: (err) => {
      const e = toApiError(err)
      toast.error(e.message, e.correlationId)
    },
  })
  const result: BulkUploadResult | undefined = upload.data
  return (
    <div className="max-w-[720px]">
      <p className="text-[14px]">
        One trade per row with the header <code className="tabular text-[12px]">trade_ref,portfolio_id,isin,side,quantity,price,trade_date,settle_date</code> (the two dates may be blank). Rows already
        uploaded with the same reference are skipped.
      </p>
      <div className="mt-5 flex flex-wrap items-center gap-3">
        <label className="inline-flex h-8 cursor-pointer items-center gap-2 rounded-[var(--radius-control)] border border-border px-3.5 text-[13px] hover:border-taupe">
          <UploadSimple size={14} /> {file ? file.name : 'Choose CSV file'}
          <input type="file" accept=".csv,text/csv" className="sr-only" onChange={(e) => setFile(e.target.files?.[0])} />
        </label>
        <Button variant="primary" disabled={!file || upload.isPending} onClick={() => file && upload.mutate(file)}>
          {upload.isPending ? 'Uploading' : 'Upload'}
        </Button>
      </div>
      {result && (
        <Section title="Upload result">
          <p className="tabular text-[14px]">
            {result.total} rows: {result.validated} validated, {result.rejected} rejected, {result.replayed} already uploaded, {result.failed} could not be read.
          </p>
          {result.errors.length > 0 && (
            <ul className="mt-3 divide-y divide-border text-[13px]">
              {result.errors.map((e) => (
                <li key={e.line} className="flex gap-4 py-2">
                  <span className="type-meta tabular w-16 shrink-0">Line {e.line}</span>
                  <span>{e.message}</span>
                </li>
              ))}
            </ul>
          )}
        </Section>
      )}
    </div>
  )
}
