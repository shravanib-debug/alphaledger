# Sample prices

`seed_closes_2026-10-14.csv`: closing prices for the 26 real demo instruments on 14-Oct-2026, filtered by hand from
the public NSE CM bhavcopy (`BhavCopy_NSE_CM_0_0_0_20261014_F_0000.csv`, column `ClsPric`) and AMFI `NAVAll.txt`
(scheme NAVs dated 14-Oct-2026), downloaded on 15-Oct-2026. Only these 26 rows are kept; raw files are never committed
(prd.md §11, compliance note). Loaded on startup by valuation-service so the demo has real prices offline.
`source` records where each price came from.

The full offline sample in the original file formats (for the bhavcopy and NAVAll parsers) arrives with the
NSE/AMFI adapters (roadmap Phase 4A, deferred part).
