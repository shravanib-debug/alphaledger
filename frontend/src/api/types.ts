/**
 * Response and request types, matching the services' Java records and row maps (checked against the controllers).
 * Records use camelCase; endpoints that return database rows as maps use snake_case column names. Dates are
 * "yyyy-MM-dd" strings; timestamps are ISO-8601 strings; amounts are JSON numbers.
 */

export type Role = 'ADMIN' | 'PM' | 'OPS_ANALYST' | 'AUDITOR'

export interface User {
  id: number
  email: string
  fullName: string
  role: Role
  status: 'ACTIVE' | 'DISABLED'
  createdAt: string
}

export interface TokenResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresIn: number
  refreshToken?: string // never present in cookie (browser) mode
  user: User
}

export interface DirectoryEntry {
  id: number
  fullName: string
  role: Role
  active: boolean
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/** Standard error body from every service (architecture.md §7.2). */
export interface ErrorBody {
  timestamp: string
  status: number
  error: string
  message: string
  path: string
  correlationId?: string
}

// ---- portfolio-service ----

export type AssetClass = 'PRIVATE_EQUITY' | 'CREDIT' | 'REAL_ESTATE'

export interface Fund {
  id: number
  name: string
  assetClass: AssetClass
}

export interface Portfolio {
  id: number
  name: string
  managerId: number
  baseCurrency: string
  status: 'ACTIVE' | 'ARCHIVED'
  funds: Fund[]
  createdAt: string
  updatedAt: string
}

export interface Instrument {
  id: number
  isin: string
  symbol: string | null
  exchange: string | null
  name: string
  assetClass: AssetClass
  assetClassProxy: string
  instrumentType: string
  currency: string
  amfiSchemeCode: string | null
  synthetic: boolean
  status: 'ACTIVE' | 'INACTIVE'
}

// ---- trade-service ----

export type TradeSide = 'BUY' | 'SELL'
export type TradeStatus = 'NEW' | 'VALIDATED' | 'SETTLED' | 'REJECTED' | 'CANCELLED'

export interface Trade {
  id: number
  tradeRef: string
  portfolioId: number
  instrumentId: number | null
  isin: string
  side: TradeSide
  quantity: number
  price: number
  notional: number
  currency: string
  tradeDate: string
  settleDate: string
  status: TradeStatus
  rejectionReason: string | null
  createdBy: number
  createdAt: string
  updatedAt: string
}

export interface CreateTradeRequest {
  tradeRef?: string
  portfolioId: number
  isin: string
  side: TradeSide
  quantity: number
  price: number
  tradeDate?: string
  settleDate?: string
}

export interface TradeAudit {
  id: number
  fromStatus: TradeStatus | null
  toStatus: TradeStatus
  changedBy: number | null
  changedAt: string
  comment: string | null
}

export interface BulkUploadResult {
  total: number
  validated: number
  rejected: number
  replayed: number
  failed: number
  errors: { line: number; message: string }[]
}

// ---- position-service ----

export interface PositionRow {
  portfolioId: number
  instrumentId: number
  isin: string
  quantity: number
  avgCost: number
  realizedPnl: number
  lastTradeId?: number | null
  updatedAt?: string
}

// ---- valuation-service ----

export interface ValuationLine {
  instrumentId: number
  isin: string
  name: string
  assetClass: string
  quantity: number
  avgCost: number
  price: number | null
  priceSource: string | null
  priceDate: string | null
  priceStale: boolean
  fairValueLevel: string | null
  marketValue: number | null
  cost: number
  unrealizedPnl: number | null
  realizedPnl: number
  pctOfTotal: number | null
}

export interface Exposure {
  key: string
  label: string
  marketValue: number
  pctOfTotal: number
}

export interface PortfolioValuation {
  marketValue: number
  totalCost: number
  unrealizedPnl: number
  realizedPnl: number
  lines: ValuationLine[]
  exposureByAssetClass: Exposure[]
  exposureByInstrument: Exposure[]
  pricedPositions: number
  stalePositions: number
  unpricedPositions: number
}

export interface ValuationResponse {
  portfolioId: number
  asOfDate: string
  valuationBasis: 'FULL_NAV' | 'MARKET_VALUE_ONLY'
  marketValue: number
  cashBalance: number
  totalLiabilities: number
  nav: number | null
  details: PortfolioValuation
}

export interface ValuationSnapshot {
  as_of_date: string
  market_value: number
  cash_balance: number
  total_liabilities: number
  nav: number | null
  valuation_basis: string
  total_cost: number
  unrealized_pnl: number
  realized_pnl: number
  priced_positions: number
  stale_positions: number
  unpriced_positions: number
}

export interface ExposureResponse {
  portfolioId: number
  marketValue: number
  byAssetClass: Exposure[]
  byInstrument: Exposure[]
}

export interface CashTransaction {
  id: number
  type: 'DEPOSIT' | 'WITHDRAWAL' | 'TRADE_BUY' | 'TRADE_SELL' | 'LIABILITY_PAYMENT' | 'BORROWING'
  amount: number
  currency: string
  reference_id: string | null
  description: string | null
  effective_date: string
  occurred_at: string
  created_by: number | null
}

export interface CashSummary {
  portfolioId: number
  currency: string
  balance: number
  transactions: CashTransaction[]
}

export type LiabilityType = 'MANAGEMENT_FEE' | 'EXPENSE' | 'PAYABLE' | 'BORROWING'

export interface Liability {
  id: number
  portfolio_id: number
  type: LiabilityType
  description: string
  amount: number
  accrual_date: string
  status: 'OPEN' | 'PAID'
  paid_date: string | null
}

export interface LatestPrice {
  instrumentId: number
  isin: string
  name: string
  price: number | null
  currency: string
  dataDate: string | null
  source: string | null
  fairValueLevel: string | null
  stale: boolean
}

export interface Holiday {
  holiday_date: string
  description: string
}

export interface LimitRule {
  id: number
  portfolio_id: number | null
  asset_class: AssetClass
  max_pct: number
}

export interface LimitBreach {
  id: number
  portfolio_id: number
  asset_class: string
  as_of_date: string
  exposure_pct: number
  max_pct: number
  market_value: number
}

export interface IngestionLog {
  id: number
  source: string
  file_name: string | null
  data_date: string | null
  status: string
  rows_loaded: number | null
  rows_rejected: number | null
  started_at: string
}

// ---- reconciliation-service ----

export type BreakType = 'MISSING_INTERNAL' | 'MISSING_EXTERNAL' | 'QTY_MISMATCH' | 'PRICE_MISMATCH'
export type BreakStatus = 'OPEN' | 'IN_REVIEW' | 'ESCALATED' | 'RESOLVED'
export type Resolution = 'ACCEPT_EXTERNAL' | 'ADJUST_INTERNAL' | 'KNOWN_DIFFERENCE'

export interface ReconRun {
  id: number
  run_date: string
  source: string
  file_name: string | null
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  total_records: number
  error_rows: number
  internal_trades: number
  matched_count: number
  break_count: number
  missing_internal: number
  missing_external: number
  qty_mismatch: number
  price_mismatch: number
  started_at: string
  completed_at: string | null
  error_message: string | null
  match_rate_pct?: number | null
}

export interface ReconBreak {
  id: number
  run_id: number
  break_type: BreakType
  trade_ref: string
  isin: string
  trade_date: string
  portfolio_id: number | null
  internal_trade_id: number | null
  internal_qty: number | null
  external_qty: number | null
  internal_price: number | null
  external_price: number | null
  status: BreakStatus
  assigned_to: number | null
  resolution_type: Resolution | null
  resolution_comment: string | null
  created_at: string
  updated_at: string
  resolved_at: string | null
  age_days: number
  overdue: boolean
}

export interface BreakAudit {
  id: number
  action: string
  performed_by: number | null
  comment: string
  performed_at: string
}

export interface RunError {
  row_number: number
  error_message: string
  raw_row: string
}

// ---- notification-service ----

export interface AppNotification {
  id: number
  type: 'RECON_BREAK' | 'LIMIT_BREACH' | 'OPEN_BREAKS_DIGEST'
  severity: 'INFO' | 'WARNING' | 'CRITICAL'
  audience_role: Role
  portfolio_id: number | null
  reference: string | null
  title: string
  message: string
  created_at: string
  read: boolean
}

export interface DeadLetter {
  partition: number
  offset: number
  key: string | null
  originalTopic: string
  exception: string | null
  value: string | null
}

// ---- analytics-service ----

export interface AumRow {
  asset_class: string
  market_value: number
  positions: number
  pct_of_total: number
}

export interface DailyPnl {
  date: string
  portfolio_id: number
  portfolio_name: string
  market_value: number
  nav: number | null
  realized_pnl: number
  unrealized_pnl: number
  total_pnl: number
  daily_pnl: number | null
}

export interface BreakTrend {
  date: string
  break_type: BreakType
  raised: number
  resolved: number
  open_at_end_of_day: number
}

export interface BreakAgeing {
  break_type: BreakType
  age_bucket: string
  open_breaks: number
  oldest_days: number
}

export interface EtlRun {
  id: number
  run_date: string
  full_refresh: boolean
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  rows_extracted: string | null
  rows_loaded: string | null
  message: string | null
  started_at: string
  finished_at: string | null
}

export interface DqResult {
  etl_run_id: number
  check_name: string
  category: string
  severity: 'CRITICAL' | 'WARNING' | 'INFO'
  status: 'PASSED' | 'FAILED'
  expected: string | null
  actual: string | null
  details: string | null
  run_at: string
}
