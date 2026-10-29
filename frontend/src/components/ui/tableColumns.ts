import { createColumnHelper, tableFeatures, type RowData } from '@tanstack/react-table'

/** Per-column presentation: numeric columns are right-aligned; `muted` columns are secondary metadata. */
export interface DataColumnMeta {
  align?: 'left' | 'right'
  muted?: boolean
}

/** Features shared by every table: core rows only (sorting and paging happen on the server). */
export const dataTableFeatures = tableFeatures({ columnMeta: {} as DataColumnMeta })
export type DataTableFeatures = typeof dataTableFeatures

/** Typed column builder for a row type: `const col = columnsFor<Trade>()`. */
export const columnsFor = <TRow extends RowData>() => createColumnHelper<DataTableFeatures, TRow>()
