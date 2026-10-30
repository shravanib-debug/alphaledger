import { Area, AreaChart, CartesianGrid, Cell, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { calendarDate, compactRupees, percent } from '../../lib/format'
import { SERIES_COLOURS } from './chartTheme'
import { Pct } from './Values'

const AXIS = { fill: 'var(--color-grey)', fontSize: 11 }
const TOOLTIP_STYLE = {
  background: 'var(--color-ivory)',
  border: '1px solid var(--color-border)',
  borderRadius: 2,
  fontSize: 12,
  color: 'var(--color-espresso)',
}

/**
 * A single value over time: thin burgundy line, faint fill, horizontal gridlines only, no animation. A visually
 * hidden table carries the same numbers for screen readers.
 */
export function ValueChart({ points, label }: { points: { date: string; value: number }[]; label: string }) {
  return (
    <figure>
      <div className="h-60" aria-hidden>
        <ResponsiveContainer>
          <AreaChart data={points} margin={{ top: 6, right: 6, bottom: 0, left: 4 }}>
            <CartesianGrid vertical={false} stroke="var(--color-border)" />
            <XAxis dataKey="date" axisLine={false} tickLine={false} tick={AXIS} tickFormatter={(d: string) => calendarDate(d).slice(0, -5)} minTickGap={24} />
            <YAxis axisLine={false} tickLine={false} tick={AXIS} width={72} domain={['auto', 'auto']} tickFormatter={(v: number) => compactRupees(v).replace('₹ ', '')} />
            <Tooltip
              cursor={{ stroke: 'var(--color-taupe)' }}
              contentStyle={TOOLTIP_STYLE}
              labelFormatter={(d) => calendarDate(String(d))}
              formatter={(v) => [compactRupees(Number(v)), label]}
            />
            <Area type="monotone" dataKey="value" stroke="var(--color-burgundy)" strokeWidth={1.5} fill="var(--color-burgundy)" fillOpacity={0.08} isAnimationActive={false} />
          </AreaChart>
        </ResponsiveContainer>
      </div>
      <table className="sr-only">
        <caption>{label} by date</caption>
        <tbody>
          {points.map((p) => (
            <tr key={p.date}>
              <td>{calendarDate(p.date)}</td>
              <td>{compactRupees(p.value)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  )
}

/** Allocation donut with the total in the centre and a legend with right-aligned shares. */
export function Donut({ slices }: { slices: { label: string; value: number }[] }) {
  const total = slices.reduce((s, x) => s + x.value, 0)
  return (
    <div className="flex flex-col items-center gap-6 sm:flex-row">
      <div className="relative h-44 w-44 shrink-0" aria-hidden>
        <ResponsiveContainer>
          <PieChart>
            <Pie data={slices} dataKey="value" nameKey="label" innerRadius={58} outerRadius={78} stroke="none" isAnimationActive={false}>
              {slices.map((s, i) => (
                <Cell key={s.label} fill={SERIES_COLOURS[i % SERIES_COLOURS.length]} />
              ))}
            </Pie>
          </PieChart>
        </ResponsiveContainer>
        <span className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
          <span className="font-serif text-[17px] tabular">{compactRupees(total)}</span>
          <span className="type-meta">Total</span>
        </span>
      </div>
      <ul className="w-full flex-1 space-y-3 text-[13px]">
        {slices.map((s, i) => (
          <li key={s.label} className="flex items-center gap-2.5">
            <span className="h-2.5 w-2.5 shrink-0 rounded-[1px]" style={{ background: SERIES_COLOURS[i % SERIES_COLOURS.length] }} aria-hidden />
            <span className="flex-1">{s.label}</span>
            <span className="sr-only">{percent(total ? (s.value / total) * 100 : 0)}</span>
            <span aria-hidden>
              <Pct value={total ? (s.value / total) * 100 : 0} />
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}
