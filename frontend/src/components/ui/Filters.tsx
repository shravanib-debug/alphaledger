import { useState, type ReactNode } from 'react'

import { Button } from './Button'

/** One hairline row of compact filters above a table; "Clear filters" appears only when something is set. */
export function FilterBar({ children, onClear }: { children: ReactNode; onClear?: () => void }) {
  return (
    <div className="mb-4 mt-6 flex flex-wrap items-end gap-x-4 gap-y-3 border-b border-border pb-4">
      {children}
      {onClear && (
        <Button size="sm" variant="ghost" onClick={onClear} className="ml-auto">
          Clear filters
        </Button>
      )}
    </div>
  )
}

const CONTROL =
  'h-8 rounded-[var(--radius-control)] border border-border bg-ivory-raised px-2 text-[13px] text-espresso hover:border-taupe focus:border-burgundy focus:outline-none focus:ring-1 focus:ring-burgundy'

export function FilterSelect({ label, value, onChange, options }: {
  label: string
  value: string
  onChange: (value: string | undefined) => void
  options: { value: string; label: string }[]
}) {
  return (
    <label className="flex flex-col gap-1">
      <span className="type-meta">{label}</span>
      <select value={value} onChange={(e) => onChange(e.target.value || undefined)} className={`${CONTROL} min-w-[140px]`}>
        <option value="">All</option>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  )
}

/** Commits on Enter or blur, so typing does not refetch on every keystroke. */
export function FilterText({ label, value, onCommit, placeholder }: {
  label: string
  value: string
  onCommit: (value: string) => void
  placeholder?: string
}) {
  const [draft, setDraft] = useState(value)
  const [synced, setSynced] = useState(value)
  if (synced !== value) {
    setSynced(value)
    setDraft(value)
  }
  return (
    <label className="flex flex-col gap-1">
      <span className="type-meta">{label}</span>
      <input
        value={draft}
        placeholder={placeholder}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={() => draft !== value && onCommit(draft)}
        onKeyDown={(e) => e.key === 'Enter' && onCommit(draft)}
        className={`${CONTROL} tabular w-[150px]`}
      />
    </label>
  )
}

export function FilterDate({ label, value, onChange }: { label: string; value: string; onChange: (value: string | undefined) => void }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="type-meta">{label}</span>
      <input type="date" value={value} onChange={(e) => onChange(e.target.value || undefined)} className={`${CONTROL} tabular`} />
    </label>
  )
}
