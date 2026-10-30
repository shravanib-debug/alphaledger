import { http } from './http'
import type { AssetClass, Instrument, Page, Portfolio } from './types'

export const portfolioApi = {
  list: () => http.get<Page<Portfolio>>('/portfolios', { params: { size: 100 } }).then((r) => r.data.content),
  get: (id: number) => http.get<Portfolio>(`/portfolios/${id}`).then((r) => r.data),
  create: (body: { name: string; managerId?: number }) => http.post<Portfolio>('/portfolios', body).then((r) => r.data),
  addFund: (id: number, body: { name: string; assetClass: AssetClass }) =>
    http.post<Portfolio>(`/portfolios/${id}/funds`, body).then((r) => r.data),
  archive: (id: number) => http.post<Portfolio>(`/portfolios/${id}/archive`).then((r) => r.data),
  instruments: (params: { q?: string; isin?: string; assetClass?: AssetClass; size?: number } = {}) =>
    http.get<Page<Instrument>>('/instruments', { params: { size: 100, ...params } }).then((r) => r.data.content),
}
