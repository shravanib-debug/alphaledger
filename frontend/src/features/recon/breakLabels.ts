import type { BreakType, Resolution } from '../../api/types'

export const BREAK_TYPE_LABELS: Record<BreakType, string> = {
  MISSING_INTERNAL: 'Missing internally',
  MISSING_EXTERNAL: 'Missing at custodian',
  QTY_MISMATCH: 'Quantity mismatch',
  PRICE_MISMATCH: 'Price mismatch',
}

export const RESOLUTION_LABELS: Record<Resolution, string> = {
  ACCEPT_EXTERNAL: 'Accept custodian value',
  ADJUST_INTERNAL: 'Adjust internal record',
  KNOWN_DIFFERENCE: 'Known difference',
}
