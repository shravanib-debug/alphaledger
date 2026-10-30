/**
 * What each role can open and do in the UI. Mirrors the services' @PreAuthorize rules (docs/ui-plan.md §4.1); the
 * services still enforce every rule, so this only keeps menus and buttons honest.
 */
import type { Role } from '../api/types'

export type Area =
  | 'overview'
  | 'portfolios'
  | 'trades'
  | 'positions'
  | 'valuation'
  | 'recon'
  | 'analytics'
  | 'notifications'
  | 'admin'

const READ: Record<Area, Role[]> = {
  overview: ['ADMIN', 'PM', 'OPS_ANALYST', 'AUDITOR'],
  portfolios: ['ADMIN', 'PM', 'OPS_ANALYST', 'AUDITOR'],
  trades: ['ADMIN', 'PM', 'OPS_ANALYST', 'AUDITOR'],
  positions: ['ADMIN', 'PM', 'OPS_ANALYST', 'AUDITOR'],
  valuation: ['ADMIN', 'PM', 'OPS_ANALYST', 'AUDITOR'],
  recon: ['ADMIN', 'OPS_ANALYST', 'AUDITOR'],
  analytics: ['ADMIN', 'PM', 'OPS_ANALYST', 'AUDITOR'],
  notifications: ['ADMIN', 'PM', 'OPS_ANALYST', 'AUDITOR'],
  admin: ['ADMIN'],
}

export type Action =
  | 'trade.create'
  | 'trade.cancel'
  | 'trade.settle'
  | 'portfolio.manage'
  | 'cash.move'
  | 'liability.manage'
  | 'recon.upload'
  | 'break.work'

const ACT: Record<Action, Role[]> = {
  'trade.create': ['ADMIN', 'PM'],
  'trade.cancel': ['ADMIN', 'PM'],
  'trade.settle': ['ADMIN'],
  'portfolio.manage': ['ADMIN', 'PM'],
  'cash.move': ['ADMIN', 'OPS_ANALYST'],
  'liability.manage': ['ADMIN', 'OPS_ANALYST'],
  'recon.upload': ['ADMIN', 'OPS_ANALYST'],
  'break.work': ['ADMIN', 'OPS_ANALYST'],
}

export function canRead(role: Role | undefined, area: Area): boolean {
  return role !== undefined && READ[area].includes(role)
}

export function can(role: Role | undefined, action: Action): boolean {
  return role !== undefined && ACT[action].includes(role)
}

export const ROLE_LABELS: Record<Role, string> = {
  ADMIN: 'Administrator',
  PM: 'Portfolio Manager',
  OPS_ANALYST: 'Operations Analyst',
  AUDITOR: 'Auditor',
}
