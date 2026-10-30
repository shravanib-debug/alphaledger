import {
  ArrowsLeftRight,
  Bell,
  ChartBar,
  ChartLineUp,
  Gear,
  List,
  Scales,
  SignOut,
  Stack,
  Suitcase,
  Vault,
  X,
  type Icon,
} from '@phosphor-icons/react'
import * as Menu from '@radix-ui/react-dropdown-menu'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router'

import { setSessionExpiredHandler } from '../../api/http'
import { notificationApi } from '../../api/notification'
import { canRead, ROLE_LABELS, type Area } from '../../auth/access'
import { useAuth } from '../../auth/AuthProvider'
import { cn } from '../../lib/cn'
import { marketDateLong } from '../../lib/format'
import { useToast } from '../ui/Toast'
import { GlobalSearch } from './GlobalSearch'

interface NavItem {
  to: string
  label: string
  icon: Icon
  area: Area
}

const MAIN: NavItem[] = [
  { to: '/', label: 'Overview', icon: ChartBar, area: 'overview' },
  { to: '/portfolios', label: 'Portfolios', icon: Suitcase, area: 'portfolios' },
  { to: '/trades', label: 'Trades', icon: ArrowsLeftRight, area: 'trades' },
  { to: '/positions', label: 'Positions', icon: Stack, area: 'positions' },
  { to: '/valuation', label: 'Valuation & NAV', icon: Vault, area: 'valuation' },
  { to: '/recon', label: 'Reconciliation', icon: Scales, area: 'recon' },
  { to: '/analytics', label: 'Analytics', icon: ChartLineUp, area: 'analytics' },
]

/** Sidebar, top bar and page outlet (docs/ui-plan.md §4). */
export function AppShell() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const toast = useToast()
  const [drawer, setDrawer] = useState(false)

  useEffect(() => {
    setSessionExpiredHandler(() => {
      toast.info('Your session ended; please sign in again.')
      navigate(`/login?next=${encodeURIComponent(location.pathname + location.search)}`, { replace: true })
    })
  }, [navigate, location, toast])

  const [drawerPath, setDrawerPath] = useState(location.pathname)
  if (drawerPath !== location.pathname) {
    setDrawerPath(location.pathname)
    setDrawer(false)
  }

  const unread = useQuery({
    queryKey: ['notifications', 'unread'],
    queryFn: notificationApi.unreadCount,
    refetchInterval: 30_000,
    enabled: !!user,
  })

  const role = user?.role
  const sidebar = (
    <nav aria-label="Main" className="flex h-full flex-col">
      <div className="px-6 pb-6 pt-7">
        <span className="font-serif text-[19px] tracking-[0.22em] text-espresso">ALPHALEDGER</span>
        <span className="mt-4 block h-px w-10 bg-taupe" />
      </div>
      <ul className="flex-1 space-y-0.5 px-3">
        {MAIN.filter((n) => canRead(role, n.area)).map((n) => (
          <li key={n.to}>
            <SideLink item={n} />
          </li>
        ))}
      </ul>
      <ul className="space-y-0.5 border-t border-border px-3 py-4">
        <li>
          <SideLink
            item={{ to: '/notifications', label: 'Notifications', icon: Bell, area: 'notifications' }}
            badge={unread.data ? unread.data : undefined}
          />
        </li>
        {canRead(role, 'admin') && (
          <li>
            <SideLink item={{ to: '/admin', label: 'Administration', icon: Gear, area: 'admin' }} />
          </li>
        )}
      </ul>
    </nav>
  )

  return (
    <div className="min-h-[100dvh] lg:grid lg:grid-cols-[240px_1fr]">
      <aside className="sticky top-0 hidden h-[100dvh] border-r border-border bg-[#f3eee7] lg:block">{sidebar}</aside>

      {drawer && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <button aria-label="Close menu" className="absolute inset-0 bg-espresso/25" onClick={() => setDrawer(false)} />
          <aside className="absolute inset-y-0 left-0 w-[260px] border-r border-border bg-[#f3eee7]">
            <button aria-label="Close menu" onClick={() => setDrawer(false)} className="absolute right-3 top-6 text-grey">
              <X size={18} />
            </button>
            {sidebar}
          </aside>
        </div>
      )}

      <div className="min-w-0">
        <header className="sticky top-0 z-30 flex h-16 items-center gap-4 border-b border-border bg-ivory/95 px-4 backdrop-blur-[2px] md:px-8">
          <button aria-label="Open menu" className="text-espresso lg:hidden" onClick={() => setDrawer(true)}>
            <List size={20} />
          </button>
          <GlobalSearch />
          <span className="type-meta ml-auto hidden whitespace-nowrap tabular md:block">{marketDateLong()}</span>
          <span className="hidden h-6 w-px bg-border md:block" />
          <Menu.Root>
            <Menu.Trigger className="flex items-center gap-2.5 rounded-[var(--radius-control)] py-1 pl-1 pr-2 hover:bg-ivory-raised">
              <span className="flex h-8 w-8 items-center justify-center rounded-full border border-taupe bg-[#efe7dd] font-serif text-[13px]">
                {initials(user?.fullName)}
              </span>
              <span className="hidden text-left leading-tight sm:block">
                <span className="block text-[13px]">{user?.fullName}</span>
                <span className="type-meta block">{role ? ROLE_LABELS[role] : ''}</span>
              </span>
            </Menu.Trigger>
            <Menu.Portal>
              <Menu.Content
                align="end"
                sideOffset={6}
                className="z-50 min-w-[200px] rounded-[var(--radius-control)] border border-border bg-ivory-raised p-1 shadow-[var(--shadow-float)]"
              >
                <div className="px-2.5 py-2">
                  <p className="text-[13px]">{user?.fullName}</p>
                  <p className="type-meta">{user?.email}</p>
                </div>
                <Menu.Separator className="my-1 h-px bg-border" />
                <Menu.Item
                  onSelect={async () => {
                    await logout()
                    navigate('/login', { replace: true })
                  }}
                  className="flex h-8 cursor-default items-center gap-2 rounded-[var(--radius-control)] px-2.5 text-[13px] outline-none data-[highlighted]:bg-burgundy-tint data-[highlighted]:text-burgundy"
                >
                  <SignOut size={14} /> Sign out
                </Menu.Item>
              </Menu.Content>
            </Menu.Portal>
          </Menu.Root>
        </header>
        <main className="mx-auto max-w-[1440px] px-4 pb-16 pt-8 md:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

function SideLink({ item, badge }: { item: NavItem; badge?: number }) {
  const IconComponent = item.icon
  return (
    <NavLink
      to={item.to}
      end={item.to === '/'}
      className={({ isActive }) =>
        cn(
          'flex h-10 items-center gap-3 rounded-[var(--radius-control)] border-l-2 px-3 text-[14px] transition-colors duration-[120ms]',
          isActive
            ? 'border-burgundy bg-burgundy-tint text-burgundy'
            : 'border-transparent text-espresso hover:bg-ivory-raised',
        )
      }
    >
      <IconComponent size={18} weight="light" aria-hidden />
      <span className="flex-1">{item.label}</span>
      {badge ? (
        <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-rose px-1.5 text-[11px] tabular text-ivory">
          <span className="sr-only">unread: </span>
          {badge}
        </span>
      ) : null}
    </NavLink>
  )
}

function initials(name: string | undefined): string {
  if (!name) {
    return ''
  }
  // Letters only: "Anita (Admin)" -> "AA" would be wrong too, so bracketed parts are dropped first.
  const parts = name.replace(/\([^)]*\)/g, ' ').trim().split(/\s+/).filter((w) => /^\p{L}/u.test(w))
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? '') : '')).toUpperCase()
}
