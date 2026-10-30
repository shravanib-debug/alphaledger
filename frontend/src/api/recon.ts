import { http } from './http'
import type { BreakAudit, BreakStatus, BreakType, ReconBreak, ReconRun, Resolution, RunError } from './types'

export interface BreakFilters {
  status?: BreakStatus
  type?: BreakType
  runId?: number
  assignedTo?: number
  minAgeDays?: number
}

export const reconApi = {
  runs: () => http.get<ReconRun[]>('/recon/runs').then((r) => r.data),
  run: (id: number) => http.get<ReconRun>(`/recon/runs/${id}`).then((r) => r.data),
  runErrors: (id: number) => http.get<RunError[]>(`/recon/runs/${id}/errors`).then((r) => r.data),
  /** 201 for a new run; 200 (duplicate: true) when the same file was already reconciled for the date. */
  upload: async (file: File, runDate: string) => {
    const form = new FormData()
    form.append('file', file)
    const res = await http.post<ReconRun>('/recon/runs', form, { params: { runDate } })
    return { run: res.data, duplicate: res.status === 200 }
  },
  breaks: (filters: BreakFilters) =>
    http.get<ReconBreak[]>('/recon/breaks', { params: { size: 500, ...filters } }).then((r) => r.data),
  break: (id: number) => http.get<ReconBreak>(`/recon/breaks/${id}`).then((r) => r.data),
  audit: (id: number) => http.get<BreakAudit[]>(`/recon/breaks/${id}/audit`).then((r) => r.data),
  assign: (id: number, comment: string, assigneeId?: number) =>
    http.post<ReconBreak>(`/recon/breaks/${id}/assign`, { assigneeId, comment }).then((r) => r.data),
  escalate: (id: number, comment: string) =>
    http.post<ReconBreak>(`/recon/breaks/${id}/escalate`, { comment }).then((r) => r.data),
  resolve: (id: number, action: Resolution, comment: string) =>
    http.post<ReconBreak>(`/recon/breaks/${id}/resolve`, { action, comment }).then((r) => r.data),
}
