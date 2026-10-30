import { http } from './http'
import type { PositionRow } from './types'

export interface PositionException {
  trade_id: number
  instrument_id: number
  reason: string
  created_at: string
}

export const positionApi = {
  list: (portfolioId: number, includeClosed = false) =>
    http.get<PositionRow[]>(`/portfolios/${portfolioId}/positions`, { params: { includeClosed } }).then((r) => r.data),
  snapshot: (portfolioId: number, date: string) =>
    http.get<PositionRow[]>(`/portfolios/${portfolioId}/positions/snapshots`, { params: { date } }).then((r) => r.data),
  exceptions: (portfolioId: number) =>
    http.get<PositionException[]>(`/portfolios/${portfolioId}/positions/exceptions`).then((r) => r.data),
}
