# Sample prices

`seed_closes_2026-10-14.csv`: closing prices for the 26 real demo instruments on 14-Oct-2026, filtered by hand from
the public NSE CM bhavcopy (`BhavCopy_NSE_CM_0_0_0_20261014_F_0000.csv`, column `ClsPric`) and AMFI `NAVAll.txt`
(scheme NAVs dated 14-Oct-2026), downloaded on 15-Oct-2026. Only these 26 rows are kept; raw files are never committed
(prd.md §11, compliance note). Loaded on startup by valuation-service so the demo has real prices offline.
`source` records where each price came from.

The offline sample in the original file formats lives next to this folder: `../nse/` (bhavcopy, 08-16 Oct 2026) and
`../amfi/` (NAVAll, 16-Oct-2026), filtered to the demo ISINs by `scripts/market-data/make-sample.sh`. The real
parsers run over them on startup in OFFLINE mode. This seed file stays because NAVAll only ever holds the latest day,
so it is the only source of the 14-Oct NAVs.
