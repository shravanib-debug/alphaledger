/** Categorical series colours, in order, so neighbours stay distinguishable (docs/ui-plan.md §3.6). */
export const SERIES_COLOURS = [
  'var(--color-burgundy)',
  'var(--color-rose)',
  'var(--color-taupe)',
  'var(--color-chart-4)',
  'var(--color-chart-5)',
]

export const ASSET_CLASS_LABELS: Record<string, string> = {
  REAL_ESTATE: 'Real estate',
  PRIVATE_EQUITY: 'Private equity',
  CREDIT: 'Credit',
  UNKNOWN: 'Unclassified',
}
