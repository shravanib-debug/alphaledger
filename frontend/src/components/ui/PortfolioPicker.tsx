import { useSelectedPortfolio } from '../../hooks/reference'
import { Select } from './Select'

/** The portfolio a page is about, kept in the URL (?portfolio=). */
export function PortfolioPicker() {
  const [selected, select, portfolios] = useSelectedPortfolio()
  return (
    <div className="flex w-[300px] max-w-full flex-col gap-1">
      <span className="type-meta" id="portfolio-picker">Portfolio</span>
      <Select
        aria-describedby="portfolio-picker"
        value={selected ? String(selected) : undefined}
        onValueChange={(v) => select(Number(v))}
        placeholder="Choose a portfolio"
        options={portfolios.map((p) => ({ value: String(p.id), label: p.name }))}
      />
    </div>
  )
}
