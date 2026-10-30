import { CaretRight, type Icon } from '@phosphor-icons/react'
import type { ReactNode } from 'react'

/**
 * The quiet links along the bottom of the overview (open breaks, pending settlements, latest ETL run): an icon, a
 * label, one serif number or line, and a sentence of detail. The whole strip is the link.
 */
export function StatusStrip({ icon: IconComponent, label, value, detail, href }: {
  icon: Icon
  label: string
  value: ReactNode
  detail?: ReactNode
  href: string
}) {
  return (
    <a
      href={href}
      className="group flex items-center gap-4 border-t border-border py-4 pr-1 transition-colors duration-[120ms] hover:bg-ivory-raised md:border-l md:border-t-0 md:px-5 md:first:border-l-0 md:first:pl-0"
    >
      <IconComponent size={20} weight="light" className="shrink-0 text-grey" aria-hidden />
      <span className="min-w-0 flex-1">
        <span className="type-meta block">{label}</span>
        <span className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5">
          <span className="whitespace-nowrap font-serif text-[24px] leading-8 tabular text-burgundy">{value}</span>
          {detail && <span className="type-meta">{detail}</span>}
        </span>
      </span>
      <CaretRight size={14} className="shrink-0 text-grey transition-transform duration-[120ms] group-hover:translate-x-0.5" />
    </a>
  )
}
