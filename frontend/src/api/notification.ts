import { http } from './http'
import type { AppNotification, DeadLetter } from './types'

export const notificationApi = {
  list: (unreadOnly = false) =>
    http.get<AppNotification[]>('/notifications', { params: { unreadOnly, size: 100 } }).then((r) => r.data),
  unreadCount: () => http.get<{ unread: number }>('/notifications/unread-count').then((r) => r.data.unread),
  markRead: (id: number) => http.post(`/notifications/${id}/read`),
  markAllRead: () => http.post<{ marked: number }>('/notifications/read-all').then((r) => r.data),
  digest: () => http.post<{ created: boolean }>('/notifications/digests').then((r) => r.data),
}

export const opsApi = {
  topics: () => http.get<string[]>('/ops/dead-letters').then((r) => r.data),
  pending: (topic: string) => http.get<DeadLetter[]>(`/ops/dead-letters/${topic}`).then((r) => r.data),
  replay: (topic: string) =>
    http.post<{ topic: string; replayed: number }>(`/ops/dead-letters/${topic}/replay`).then((r) => r.data),
}
