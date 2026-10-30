import { MagnifyingGlass } from '@phosphor-icons/react'
import * as Popover from '@radix-ui/react-popover'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router'

import { tradeApi } from '../../api/trade'
import { useInstruments, usePortfolios } from '../../hooks/reference'
import { cn } from '../../lib/cn'

interface Result {
  group: 'Instruments' | 'Portfolios' | 'Trades'
  label: string
  detail: string
  to: string
}

/**
 * One box for instruments (name, symbol or ISIN), portfolios and trades (by reference or ISIN). Instruments and
 * portfolios are searched in the cached reference data; trades through the blotter API. "/" focuses the box.
 */
export function GlobalSearch() {
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const input = useRef<HTMLInputElement>(null)
  const navigate = useNavigate()
  const instruments = useInstruments()
  const portfolios = usePortfolios()
  const q = query.trim().toLowerCase()

  const isinLike = /^[a-z]{2}[a-z0-9]{10}$/i.test(query.trim())
  const trades = useQuery({
    queryKey: ['search-trades', q],
    queryFn: () => tradeApi.list(isinLike ? { isin: query.trim().toUpperCase(), size: 5 } : { size: 100 }),
    enabled: q.length >= 2,
    staleTime: 30_000,
  })

  const results = useMemo<Result[]>(() => {
    if (q.length < 2) {
      return []
    }
    const out: Result[] = []
    for (const i of instruments.data ?? []) {
      if (`${i.name} ${i.symbol ?? ''} ${i.isin}`.toLowerCase().includes(q)) {
        out.push({ group: 'Instruments', label: i.name, detail: i.isin, to: `/trades?isin=${i.isin}` })
      }
    }
    for (const p of portfolios.data ?? []) {
      if (p.name.toLowerCase().includes(q)) {
        out.push({ group: 'Portfolios', label: p.name, detail: p.status === 'ACTIVE' ? 'Active' : 'Archived', to: `/portfolios/${p.id}` })
      }
    }
    for (const t of trades.data?.content ?? []) {
      if (t.tradeRef.toLowerCase().includes(q) || t.isin.toLowerCase() === q) {
        out.push({ group: 'Trades', label: t.tradeRef, detail: `${t.side} ${t.isin}`, to: `/trades/${t.id}` })
      }
    }
    return out.slice(0, 12)
  }, [q, instruments.data, portfolios.data, trades.data])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement
      if (e.key === '/' && !['INPUT', 'TEXTAREA'].includes(target.tagName) && !target.isContentEditable) {
        e.preventDefault()
        input.current?.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const go = (r: Result) => {
    setOpen(false)
    setQuery('')
    navigate(r.to)
  }

  return (
    <Popover.Root open={open && results.length > 0} onOpenChange={setOpen}>
      <Popover.Anchor asChild>
        <div className="relative w-full max-w-[480px]">
          <MagnifyingGlass size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-grey" />
          <input
            ref={input}
            type="search"
            role="combobox"
            aria-expanded={open && results.length > 0}
            aria-label="Search instruments, trades or portfolios"
            placeholder="Search instruments, trades, or portfolios"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value)
              setActive(0)
              setOpen(true)
            }}
            onFocus={() => setOpen(true)}
            onKeyDown={(e) => {
              if (e.key === 'ArrowDown') {
                e.preventDefault()
                setActive((a) => Math.min(a + 1, results.length - 1))
              } else if (e.key === 'ArrowUp') {
                e.preventDefault()
                setActive((a) => Math.max(a - 1, 0))
              } else if (e.key === 'Enter' && results[active]) {
                go(results[active])
              } else if (e.key === 'Escape') {
                setOpen(false)
              }
            }}
            className="h-9 w-full rounded-[var(--radius-control)] border border-border bg-ivory-raised pl-9 pr-3 text-[13px] placeholder:text-grey hover:border-taupe focus:border-burgundy focus:outline-none focus:ring-1 focus:ring-burgundy"
          />
        </div>
      </Popover.Anchor>
      <Popover.Portal>
        <Popover.Content
          align="start"
          sideOffset={6}
          onOpenAutoFocus={(e) => e.preventDefault()}
          className="z-50 w-[var(--radix-popover-trigger-width)] min-w-[320px] max-w-[480px] rounded-[var(--radius-control)] border border-border bg-ivory-raised py-1 shadow-[var(--shadow-float)]"
        >
          <ul role="listbox">
            {results.map((r, i) => (
              <li key={`${r.group}-${r.to}`}>
                {(i === 0 || results[i - 1]?.group !== r.group) && <p className="type-label px-3 pb-1 pt-2">{r.group}</p>}
                <button
                  type="button"
                  role="option"
                  aria-selected={i === active}
                  onMouseEnter={() => setActive(i)}
                  onClick={() => go(r)}
                  className={cn('flex w-full items-baseline justify-between gap-3 px-3 py-1.5 text-left text-[13px]', i === active && 'bg-burgundy-tint text-burgundy')}
                >
                  <span className="truncate">{r.label}</span>
                  <span className="type-meta tabular shrink-0">{r.detail}</span>
                </button>
              </li>
            ))}
          </ul>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}
