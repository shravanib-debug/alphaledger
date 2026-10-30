import { http } from './http'
import type { BulkUploadResult, CreateTradeRequest, Page, Trade, TradeAudit, TradeSide, TradeStatus } from './types'

export interface TradeFilters {
  portfolioId?: number
  status?: TradeStatus
  side?: TradeSide
  isin?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

export const tradeApi = {
  list: (filters: TradeFilters) => http.get<Page<Trade>>('/trades', { params: filters }).then((r) => r.data),
  get: (id: number) => http.get<Trade>(`/trades/${id}`).then((r) => r.data),
  audit: (id: number) => http.get<TradeAudit[]>(`/trades/${id}/audit`).then((r) => r.data),
  create: (body: CreateTradeRequest) => http.post<Trade>('/trades', body).then((r) => r.data),
  cancel: (id: number, comment?: string) => http.post<Trade>(`/trades/${id}/cancel`, { comment }).then((r) => r.data),
  settle: (id: number, comment?: string) => http.post<Trade>(`/trades/${id}/settle`, { comment }).then((r) => r.data),
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<BulkUploadResult>('/trades/upload', form).then((r) => r.data)
  },
}
