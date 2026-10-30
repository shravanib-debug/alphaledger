/**
 * Mock API for flow tests (MSW): the real request paths, with fixtures taken from the demo data (portfolios, real
 * ISINs, 14/15-Oct-2026 closes). Tests override individual handlers with server.use(...).
 */
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'

import type {
  AppNotification,
  CashSummary,
  DirectoryEntry,
  Instrument,
  Page,
  Portfolio,
  ReconBreak,
  Role,
  TokenResponse,
  Trade,
  User,
  ValuationResponse,
} from '../api/types'

export const API = '/api/v1'

export const USERS: Record<Role, User> = {
  ADMIN: { id: 1, email: 'anita.admin@alphaledger.test', fullName: 'Anita Rao', role: 'ADMIN', status: 'ACTIVE', createdAt: '2026-10-15T00:00:00Z' },
  PM: { id: 2, email: 'priya.pm@alphaledger.test', fullName: 'Priya Patel', role: 'PM', status: 'ACTIVE', createdAt: '2026-10-15T00:00:00Z' },
  OPS_ANALYST: { id: 3, email: 'rohan.ops@alphaledger.test', fullName: 'Rohan Mehta', role: 'OPS_ANALYST', status: 'ACTIVE', createdAt: '2026-10-15T00:00:00Z' },
  AUDITOR: { id: 4, email: 'vikram.auditor@alphaledger.test', fullName: 'Vikram Singh', role: 'AUDITOR', status: 'ACTIVE', createdAt: '2026-10-15T00:00:00Z' },
}

export function tokens(role: Role): TokenResponse {
  return { accessToken: `access-${role}`, tokenType: 'Bearer', expiresIn: 900, user: USERS[role] }
}

export const PORTFOLIOS: Portfolio[] = [
  { id: 2, name: 'AlphaLedger Bharat Real Assets Fund', managerId: 2, baseCurrency: 'INR', status: 'ACTIVE', funds: [{ id: 10, name: 'Listed REITs', assetClass: 'REAL_ESTATE' }], createdAt: '2026-10-15T00:00:00Z', updatedAt: '2026-10-15T00:00:00Z' },
]

export const INSTRUMENTS: Instrument[] = [
  { id: 1, isin: 'INE041025011', symbol: 'EMBASSY', exchange: 'NSE', name: 'Embassy Office Parks REIT', assetClass: 'REAL_ESTATE', assetClassProxy: 'LISTED_REIT', instrumentType: 'REIT', currency: 'INR', amfiSchemeCode: null, synthetic: false, status: 'ACTIVE' },
]

export const DIRECTORY: DirectoryEntry[] = Object.values(USERS).map((u) => ({ id: u.id, fullName: u.fullName, role: u.role, active: true }))

export const VALUATION: ValuationResponse = {
  portfolioId: 2,
  asOfDate: '2026-10-15',
  valuationBasis: 'FULL_NAV',
  marketValue: 217455,
  cashBalance: 85000,
  totalLiabilities: 2505.96,
  nav: 299949.04,
  details: {
    marketValue: 217455,
    totalCost: 215000,
    unrealizedPnl: 2455,
    realizedPnl: 0,
    lines: [
      { instrumentId: 1, isin: 'INE041025011', name: 'Embassy Office Parks REIT', assetClass: 'REAL_ESTATE', quantity: 500, avgCost: 430, price: 434.91, priceSource: 'NSE_BHAVCOPY', priceDate: '2026-10-14', priceStale: true, fairValueLevel: null, marketValue: 217455, cost: 215000, unrealizedPnl: 2455, realizedPnl: 0, pctOfTotal: 100 },
    ],
    exposureByAssetClass: [{ key: 'REAL_ESTATE', label: 'REAL_ESTATE', marketValue: 217455, pctOfTotal: 100 }],
    exposureByInstrument: [],
    pricedPositions: 1,
    stalePositions: 1,
    unpricedPositions: 0,
  },
}

export const TRADE: Trade = {
  id: 101, tradeRef: 'TR-101', portfolioId: 2, instrumentId: 1, isin: 'INE041025011', side: 'BUY', quantity: 500, price: 430, notional: 215000, currency: 'INR',
  tradeDate: '2026-10-13', settleDate: '2026-10-14', status: 'SETTLED', rejectionReason: null, createdBy: 2, createdAt: '2026-10-13T04:30:00Z', updatedAt: '2026-10-14T12:30:00Z',
}

export const BREAK: ReconBreak = {
  id: 7, run_id: 3, break_type: 'QTY_MISMATCH', trade_ref: 'TR-101', isin: 'INE041025011', trade_date: '2026-10-13', portfolio_id: 2, internal_trade_id: 101,
  internal_qty: 500, external_qty: 490, internal_price: 430, external_price: 430, status: 'OPEN', assigned_to: null, resolution_type: null, resolution_comment: null,
  created_at: '2026-10-15T04:30:00Z', updated_at: '2026-10-15T04:30:00Z', resolved_at: null, age_days: 0, overdue: false,
}

const page = <T,>(content: T[]): Page<T> => ({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 })

/** The refresh cookie's state, per test: which role (if any) a refresh restores. */
export const session = { role: null as Role | null }

export const handlers = [
  http.post(`${API}/auth/refresh`, () => (session.role ? HttpResponse.json(tokens(session.role)) : HttpResponse.json({ status: 401, error: 'INVALID_REFRESH_TOKEN', message: 'Refresh token is invalid, expired or revoked' }, { status: 401 }))),
  http.post(`${API}/auth/login`, async ({ request }) => {
    const body = (await request.json()) as { email: string; password: string }
    const user = Object.values(USERS).find((u) => u.email === body.email)
    if (!user || body.password !== 'Demo@AlphaLedger2026') {
      return HttpResponse.json({ status: 401, error: 'INVALID_CREDENTIALS', message: 'Invalid email or password' }, { status: 401 })
    }
    session.role = user.role
    return HttpResponse.json(tokens(user.role))
  }),
  http.post(`${API}/auth/logout`, () => {
    session.role = null
    return new HttpResponse(null, { status: 204 })
  }),
  http.get(`${API}/auth/users/directory`, () => HttpResponse.json(DIRECTORY)),
  http.get(`${API}/portfolios`, () => HttpResponse.json(page(PORTFOLIOS))),
  http.get(`${API}/instruments`, () => HttpResponse.json(page(INSTRUMENTS))),
  http.get(`${API}/portfolios/:id/valuation`, () => HttpResponse.json(VALUATION)),
  http.get(`${API}/portfolios/:id/valuation/history`, () => HttpResponse.json([])),
  http.get(`${API}/portfolios/:id/cash`, () => HttpResponse.json({ portfolioId: 2, currency: 'INR', balance: 85000, transactions: [] } satisfies CashSummary)),
  http.get(`${API}/portfolios/:id/liabilities`, () => HttpResponse.json([])),
  http.get(`${API}/valuations/limit-rules`, () => HttpResponse.json([])),
  http.get(`${API}/valuations/limit-breaches`, () => HttpResponse.json([])),
  http.get(`${API}/trades`, () => HttpResponse.json(page([TRADE]))),
  http.get(`${API}/trades/:id`, () => HttpResponse.json(TRADE)),
  http.get(`${API}/trades/:id/audit`, () => HttpResponse.json([])),
  http.get(`${API}/recon/breaks`, () => HttpResponse.json([BREAK])),
  http.get(`${API}/recon/breaks/:id`, () => HttpResponse.json(BREAK)),
  http.get(`${API}/recon/breaks/:id/audit`, () => HttpResponse.json([])),
  http.get(`${API}/notifications/unread-count`, () => HttpResponse.json({ unread: 2 })),
  http.get(`${API}/notifications`, () => HttpResponse.json([] as AppNotification[])),
  http.get(`${API}/analytics/etl-runs`, () => HttpResponse.json([])),
  http.get(`${API}/analytics/dq-results`, () => HttpResponse.json([])),
  http.get(`${API}/marketdata/prices/latest`, () => HttpResponse.json([{ instrumentId: 1, isin: 'INE041025011', name: 'Embassy Office Parks REIT', price: 434.91, currency: 'INR', dataDate: '2026-10-14', source: 'NSE_BHAVCOPY', fairValueLevel: null, stale: true }])),
  http.get(`${API}/marketdata/holidays`, () => HttpResponse.json([{ holiday_date: '2026-10-20', description: 'Dussehra' }])),
]

export const server = setupServer(...handlers)
