import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router'

import { toApiError } from '../../api/http'
import { notificationApi } from '../../api/notification'
import type { AppNotification } from '../../api/types'
import { Button } from '../../components/ui/Button'
import { PageHeader } from '../../components/ui/Layout'
import { EmptyState, ErrorState, SkeletonRows } from '../../components/ui/States'
import { Tag } from '../../components/ui/Tag'
import { cn } from '../../lib/cn'
import { timestamp } from '../../lib/format'

/** Alerts for the user's role, with their own read state; each links to what it is about (docs/ui-plan.md §6.8). */
export function NotificationsPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [unreadOnly, setUnreadOnly] = useState(false)
  const list = useQuery({ queryKey: ['notifications', 'list', unreadOnly], queryFn: () => notificationApi.list(unreadOnly), refetchInterval: 30_000 })

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['notifications'] })
  const markRead = useMutation({ mutationFn: (id: number) => notificationApi.markRead(id), onSuccess: refresh })
  const markAll = useMutation({ mutationFn: notificationApi.markAllRead, onSuccess: refresh })

  const open = (n: AppNotification) => {
    if (!n.read) {
      markRead.mutate(n.id)
    }
    if (n.type === 'RECON_BREAK' && n.reference) {
      navigate(`/recon/breaks/${n.reference}`)
    } else if (n.type === 'OPEN_BREAKS_DIGEST') {
      navigate('/recon/breaks?status=OPEN')
    } else if (n.type === 'LIMIT_BREACH' && n.portfolio_id) {
      navigate(`/valuation?portfolio=${n.portfolio_id}`)
    }
  }
  const error = list.error ? toApiError(list.error) : undefined

  return (
    <>
      <PageHeader
        eyebrow="Notifications"
        title="What needs your attention."
        description="Reconciliation breaks reach Operations; limit breaches reach portfolio managers. Each is also emailed."
        aside={
          <div className="flex gap-2">
            <Button size="sm" variant={unreadOnly ? 'secondary' : 'ghost'} onClick={() => setUnreadOnly((u) => !u)}>
              {unreadOnly ? 'Showing unread' : 'Unread only'}
            </Button>
            <Button size="sm" onClick={() => markAll.mutate()} disabled={markAll.isPending}>Mark all read</Button>
          </div>
        }
      />
      <div className="mt-4">
        {error ? (
          <ErrorState message={error.message} correlationId={error.correlationId} onRetry={() => list.refetch()} />
        ) : list.isLoading ? (
          <SkeletonRows rows={5} columns={2} />
        ) : (list.data ?? []).length === 0 ? (
          <EmptyState message={unreadOnly ? 'Nothing unread.' : 'No notifications yet.'} />
        ) : (
          <ul className="divide-y divide-border border-b border-border">
            {list.data!.map((n) => (
              <li key={n.id}>
                <button
                  type="button"
                  onClick={() => open(n)}
                  className={cn('grid w-full grid-cols-[auto_1fr_auto] items-start gap-4 py-4 text-left transition-colors duration-[120ms] hover:bg-ivory-raised', !n.read && 'bg-ivory-raised/60')}
                >
                  <span className={cn('mt-1.5 h-2 w-2 rounded-full', n.read ? 'bg-transparent' : 'bg-burgundy')} aria-hidden />
                  <span className="min-w-0">
                    <span className={cn('block text-[14px]', !n.read && 'font-medium')}>
                      {!n.read && <span className="sr-only">Unread: </span>}
                      {n.title}
                    </span>
                    <span className="mt-1 block whitespace-pre-line text-[13px] text-grey">{n.message}</span>
                  </span>
                  <span className="flex flex-col items-end gap-1.5">
                    <Tag tone={n.severity === 'CRITICAL' ? 'negative' : n.severity === 'WARNING' ? 'warn' : 'outline'}>{n.severity}</Tag>
                    <span className="type-meta whitespace-nowrap">{timestamp(n.created_at)}</span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  )
}
