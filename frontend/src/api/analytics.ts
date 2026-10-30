import { http } from './http'
import type { AumRow, BreakAgeing, BreakTrend, DailyPnl, DqResult, EtlRun } from './types'

export const analyticsApi = {
  aum: (date?: string) =>
    http.get<{ date: string; rows: AumRow[] }>('/analytics/aum-by-asset-class', { params: { date } }).then((r) => r.data),
  dailyPnl: (params: { portfolioId?: number; from?: string; to?: string }) =>
    http.get<DailyPnl[]>('/analytics/daily-pnl', { params }).then((r) => r.data),
  breakTrend: (from?: string, to?: string) =>
    http.get<BreakTrend[]>('/analytics/break-trend', { params: { from, to } }).then((r) => r.data),
  breakAgeing: () => http.get<BreakAgeing[]>('/analytics/break-ageing').then((r) => r.data),
  etlRuns: () => http.get<EtlRun[]>('/analytics/etl-runs').then((r) => r.data),
  dqResults: (runId?: number) => http.get<DqResult[]>('/analytics/dq-results', { params: { runId } }).then((r) => r.data),
  runEtl: (date?: string, fullRefresh = false) =>
    http.post<EtlRun>('/analytics/etl-runs', null, { params: { date, fullRefresh } }).then((r) => r.data),
}
