/** Sums of API amounts in integer paise, so adding several portfolios never drifts by a paisa. */
export function sumAmounts(values: (number | null | undefined)[]): number {
  let paise = 0
  for (const v of values) {
    if (v !== null && v !== undefined) {
      paise += Math.round(v * 100)
    }
  }
  return paise / 100
}
