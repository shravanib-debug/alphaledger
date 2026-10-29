import type { ReactNode } from 'react'

import { cn } from '../../lib/cn'

/** Eyebrow, serif headline, serif description, and an optional right-hand slot (e.g. the "As of" date). */
export function PageHeader({ eyebrow, title, description, aside }: {
  eyebrow?: string
  title: string
  description?: string
  aside?: ReactNode
}) {
  return (
    <header className="flex flex-col gap-6 border-b border-border pb-7 md:flex-row md:items-end md:justify-between">
      <div className="max-w-[720px]">
        {eyebrow && <p className="type-label mb-3 text-burgundy">{eyebrow}</p>}
        <h1 className="type-headline">{title}</h1>
        {description && <p className="type-description mt-2.5">{description}</p>}
      </div>
      {aside && <div className="shrink-0">{aside}</div>}
    </header>
  )
}

/** A serif section title with optional actions on the right, and a hairline beneath. */
export function Section({ title, actions, children, className }: {
  title: string
  actions?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <section className={cn('mt-10', className)}>
      <div className="mb-3 flex items-baseline justify-between gap-4 border-b border-border pb-2.5">
        <h2 className="type-section">{title}</h2>
        {actions && <div className="flex items-center gap-2 text-[13px]">{actions}</div>}
      </div>
      {children}
    </section>
  )
}

/** The only framed surface: for content that needs a frame (charts). Everything else sits on the page. */
export function Panel({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cn('rounded-[var(--radius-panel)] border border-border bg-ivory-raised p-5', className)}>
      {children}
    </div>
  )
}

/** Summary figures separated by vertical hairlines (not cards); wraps to two rows on narrow screens. */
export function FigureRow({ children }: { children: ReactNode }) {
  return (
    <div className="grid grid-cols-2 gap-y-6 py-6 md:grid-cols-[repeat(auto-fit,minmax(0,1fr))] md:divide-x md:divide-border">
      {children}
    </div>
  )
}

export function Figure({ label, value, detail }: { label: string; value: ReactNode; detail?: ReactNode }) {
  return (
    <div className="min-w-0 pr-6 md:px-7 md:first:pl-0">
      <p className="type-label">{label}</p>
      <p className="type-figure mt-2 truncate">{value}</p>
      {detail && <p className="type-meta mt-1.5">{detail}</p>}
    </div>
  )
}
