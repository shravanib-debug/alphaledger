import { lazy, Suspense, type ReactNode } from 'react'
import { Link, Outlet, type RouteObject } from 'react-router'

import type { Area } from './auth/access'
import { AuthProvider, RequireArea, RequireAuth } from './auth/AuthProvider'
import { AppShell } from './components/layout/AppShell'
import { LoginPage } from './features/login/LoginPage'

// Each screen is its own chunk, loaded the first time it is opened; only login and the shell are in the main bundle.
const OverviewPage = lazy(() => import('./features/overview/OverviewPage').then((m) => ({ default: m.OverviewPage })))
const AdminPage = lazy(() => import('./features/admin/AdminPage').then((m) => ({ default: m.AdminPage })))
const AnalyticsPage = lazy(() => import('./features/analytics/AnalyticsPage').then((m) => ({ default: m.AnalyticsPage })))
const NotificationsPage = lazy(() => import('./features/notifications/NotificationsPage').then((m) => ({ default: m.NotificationsPage })))
const PortfolioDetailPage = lazy(() => import('./features/portfolios/PortfoliosPage').then((m) => ({ default: m.PortfolioDetailPage })))
const PortfoliosPage = lazy(() => import('./features/portfolios/PortfoliosPage').then((m) => ({ default: m.PortfoliosPage })))
const PositionsPage = lazy(() => import('./features/positions/PositionsPage').then((m) => ({ default: m.PositionsPage })))
const BreakDetailPage = lazy(() => import('./features/recon/BreakDetailPage').then((m) => ({ default: m.BreakDetailPage })))
const BreaksPage = lazy(() => import('./features/recon/BreaksPage').then((m) => ({ default: m.BreaksPage })))
const ReconPage = lazy(() => import('./features/recon/ReconPage').then((m) => ({ default: m.ReconPage })))
const StyleGuidePage = lazy(() => import('./features/style-guide/StyleGuidePage').then((m) => ({ default: m.StyleGuidePage })))
const NewTradePage = lazy(() => import('./features/trades/NewTradePage').then((m) => ({ default: m.NewTradePage })))
const TradeDetailPage = lazy(() => import('./features/trades/TradeDetailPage').then((m) => ({ default: m.TradeDetailPage })))
const TradesPage = lazy(() => import('./features/trades/TradesPage').then((m) => ({ default: m.TradesPage })))
const ValuationPage = lazy(() => import('./features/valuation/ValuationPage').then((m) => ({ default: m.ValuationPage })))

const guard = (area: Area, page: ReactNode) => (
  <RequireArea area={area}>
    <Suspense fallback={<PageLoading />}>{page}</Suspense>
  </RequireArea>
)

function PageLoading() {
  return (
    <div role="status" className="py-16">
      <span className="type-label">Loading</span>
    </div>
  )
}

/** The app's routes; App mounts them in a browser router, tests in a memory router. */
export const routes: RouteObject[] = [
  {
    element: (
      <AuthProvider>
        <Outlet />
      </AuthProvider>
    ),
    children: [
      { path: '/login', element: <LoginPage /> },
      ...(import.meta.env.DEV ? [{ path: '/style-guide', element: <Suspense fallback={<PageLoading />}><StyleGuidePage /></Suspense> }] : []),
      {
        element: (
          <RequireAuth>
            <AppShell />
          </RequireAuth>
        ),
        children: [
          { path: '/', element: guard('overview', <OverviewPage />) },
          { path: '/portfolios', element: guard('portfolios', <PortfoliosPage />) },
          { path: '/portfolios/:id', element: guard('portfolios', <PortfolioDetailPage />) },
          { path: '/trades', element: guard('trades', <TradesPage />) },
          { path: '/trades/new', element: guard('trades', <NewTradePage />) },
          { path: '/trades/:id', element: guard('trades', <TradeDetailPage />) },
          { path: '/positions', element: guard('positions', <PositionsPage />) },
          { path: '/valuation', element: guard('valuation', <ValuationPage />) },
          { path: '/recon', element: guard('recon', <ReconPage />) },
          { path: '/recon/breaks', element: guard('recon', <BreaksPage />) },
          { path: '/recon/breaks/:id', element: guard('recon', <BreakDetailPage />) },
          { path: '/analytics', element: guard('analytics', <AnalyticsPage />) },
          { path: '/notifications', element: guard('notifications', <NotificationsPage />) },
          { path: '/admin', element: guard('admin', <AdminPage />) },
          { path: '*', element: <NotFound /> },
        ],
      },
    ],
  },
]

function NotFound() {
  return (
    <div className="py-16">
      <p className="type-label text-burgundy">Not found</p>
      <h1 className="type-headline mt-3">There is no page here.</h1>
      <Link to="/" className="mt-4 inline-block text-[13px] text-burgundy">Back to the overview</Link>
    </div>
  )
}

