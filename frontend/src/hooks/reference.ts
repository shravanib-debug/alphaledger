/**
 * Reference data every screen shares, fetched once and cached: portfolios, the instrument master and the user
 * directory. Tables join on these locally instead of calling a service per row.
 */
import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useSearchParams } from 'react-router'

import { authApi } from '../api/auth'
import { portfolioApi } from '../api/portfolio'
import type { DirectoryEntry, Instrument, Portfolio } from '../api/types'

const HOUR = 60 * 60 * 1000

export function usePortfolios() {
  return useQuery({ queryKey: ['portfolios'], queryFn: portfolioApi.list, staleTime: 5 * 60 * 1000 })
}

export function useInstruments() {
  return useQuery({ queryKey: ['instruments'], queryFn: () => portfolioApi.instruments(), staleTime: HOUR })
}

export function useDirectory() {
  return useQuery({ queryKey: ['directory'], queryFn: authApi.directory, staleTime: HOUR })
}

export function usePortfolioNames(): (id: number | null | undefined) => string {
  const { data } = usePortfolios()
  return useMemo(() => lookup(data, (p: Portfolio) => p.name, 'Portfolio'), [data])
}

export function useUserNames(): (id: number | null | undefined) => string {
  const { data } = useDirectory()
  return useMemo(() => lookup(data, (u: DirectoryEntry) => u.fullName, 'User'), [data])
}

export function useInstrumentsById(): Map<number, Instrument> {
  const { data } = useInstruments()
  return useMemo(() => new Map((data ?? []).map((i) => [i.id, i])), [data])
}

export function useInstrumentsByIsin(): Map<string, Instrument> {
  const { data } = useInstruments()
  return useMemo(() => new Map((data ?? []).map((i) => [i.isin, i])), [data])
}

function lookup<T extends { id: number }>(rows: T[] | undefined, name: (row: T) => string, kind: string) {
  const byId = new Map((rows ?? []).map((r) => [r.id, name(r)]))
  return (id: number | null | undefined) => (id === null || id === undefined ? '' : (byId.get(id) ?? `${kind} ${id}`))
}

/**
 * The selected portfolio, kept in the URL (?portfolio=2) so links are shareable; defaults to the first active one.
 */
export function useSelectedPortfolio(): [number | undefined, (id: number) => void, Portfolio[]] {
  const [params, setParams] = useSearchParams()
  const { data } = usePortfolios()
  const active = (data ?? []).filter((p) => p.status === 'ACTIVE')
  const fromUrl = Number(params.get('portfolio'))
  const selected = active.some((p) => p.id === fromUrl) ? fromUrl : active[0]?.id
  const select = (id: number) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev)
        next.set('portfolio', String(id))
        return next
      },
      { replace: true },
    )
  return [selected, select, active]
}
