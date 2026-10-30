import { http } from './http'
import type {
  AssetClass,
  CashSummary,
  ExposureResponse,
  Holiday,
  IngestionLog,
  LatestPrice,
  Liability,
  LiabilityType,
  LimitBreach,
  LimitRule,
  ValuationResponse,
  ValuationSnapshot,
} from './types'

export const valuationApi = {
  valuation: (portfolioId: number, asOf?: string) =>
    http.get<ValuationResponse>(`/portfolios/${portfolioId}/valuation`, { params: { asOf } }).then((r) => r.data),
  history: (portfolioId: number, from?: string, to?: string) =>
    http.get<ValuationSnapshot[]>(`/portfolios/${portfolioId}/valuation/history`, { params: { from, to } }).then((r) => r.data),
  exposure: (portfolioId: number, asOf?: string) =>
    http.get<ExposureResponse>(`/portfolios/${portfolioId}/exposure`, { params: { asOf } }).then((r) => r.data),

  cash: (portfolioId: number) => http.get<CashSummary>(`/portfolios/${portfolioId}/cash`).then((r) => r.data),
  moveCash: (portfolioId: number, body: { type: 'DEPOSIT' | 'WITHDRAWAL'; amount: number; description?: string; effectiveDate?: string }) =>
    http.post<CashSummary>(`/portfolios/${portfolioId}/cash`, body).then((r) => r.data),
  liabilities: (portfolioId: number, status?: 'OPEN' | 'PAID') =>
    http.get<Liability[]>(`/portfolios/${portfolioId}/liabilities`, { params: { status } }).then((r) => r.data),
  addLiability: (portfolioId: number, body: { type: LiabilityType; description: string; amount: number; accrualDate?: string }) =>
    http.post<Liability>(`/portfolios/${portfolioId}/liabilities`, body).then((r) => r.data),
  payLiability: (portfolioId: number, id: number) =>
    http.post<Liability>(`/portfolios/${portfolioId}/liabilities/${id}/pay`).then((r) => r.data),
  deleteLiability: (portfolioId: number, id: number) => http.delete(`/portfolios/${portfolioId}/liabilities/${id}`),

  limitRules: () => http.get<LimitRule[]>('/valuations/limit-rules').then((r) => r.data),
  saveLimitRule: (body: { portfolioId?: number; assetClass: AssetClass; maxPct: number }) =>
    http.post<LimitRule>('/valuations/limit-rules', body).then((r) => r.data),
  deleteLimitRule: (id: number) => http.delete(`/valuations/limit-rules/${id}`),
  limitBreaches: (portfolioId?: number) =>
    http.get<LimitBreach[]>('/valuations/limit-breaches', { params: { portfolioId } }).then((r) => r.data),
  runEndOfDay: (date?: string) =>
    http.post<{ valuationDate: string; status: string }>('/valuations/eod-runs', null, { params: { date } }).then((r) => r.data),
  accrueFees: (date?: string) =>
    http.post<{ accrualDate: string; portfoliosCharged: number }>('/valuations/fee-accruals', null, { params: { date } }).then((r) => r.data),
}

export const marketDataApi = {
  latest: (isin?: string) => http.get<LatestPrice[]>('/marketdata/prices/latest', { params: { isin } }).then((r) => r.data),
  holidays: (year?: number) => http.get<Holiday[]>('/marketdata/holidays', { params: { year } }).then((r) => r.data),
  ingestions: () => http.get<IngestionLog[]>('/marketdata/ingestions', { params: { limit: 50 } }).then((r) => r.data),
}
