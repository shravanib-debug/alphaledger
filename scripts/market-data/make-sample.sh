#!/usr/bin/env bash
# Builds the committed offline sample from the local cache: each bhavcopy and NAVAll file is filtered down to the
# demo instruments (reference-data/instruments/instrument_master.csv), keeping the original format so the real
# parsers run on it. Only these few rows are committed; full raw files stay in the git-ignored cache.
#   bash scripts/market-data/download.sh 2026-10-08 ... 2026-10-16 --amfi
#   bash scripts/market-data/make-sample.sh
set -euo pipefail

CACHE=${MARKET_CACHE_DIR:-data/market-cache}
OUT=reference-data/sample
MASTER=reference-data/instruments/instrument_master.csv
mkdir -p "$OUT/nse" "$OUT/amfi"

isins=$(awk -F, 'NR > 1 && $2 ~ /^IN/ {print $2}' "$MASTER" | sort -u | paste -sd'|')

for zip in "$CACHE"/NSE_BHAVCOPY/*/BhavCopy_NSE_CM_*.csv.zip; do
    [[ -e "$zip" ]] || continue
    name=$(basename "$zip" .zip)
    unzip -p "$zip" | tr -d '\r' | awk -F, -v isins="$isins" '
        BEGIN { n = split(isins, a, "|"); for (i = 1; i <= n; i++) want[a[i]] = 1 }
        NR == 1 { for (i = 1; i <= NF; i++) if ($i == "ISIN") col = i; print; next }
        ($col in want) { print }' > "$OUT/nse/$name"
    echo "$OUT/nse/$name: $(($(wc -l < "$OUT/nse/$name") - 1)) rows"
done

for nav in "$CACHE"/AMFI_NAV/*/NAVAll_*.txt; do
    [[ -e "$nav" ]] || continue
    name=$(basename "$nav")
    # Keep the header, and for each demo fund its scheme-category and AMC heading lines, so the sample still has
    # the non-data lines the parser must skip.
    tr -d '\r' < "$nav" | awk -F';' -v isins="$isins" '
        BEGIN { n = split(isins, a, "|"); for (i = 1; i <= n; i++) want[a[i]] = 1 }
        NR == 1 { print; next }
        NF == 1 && $0 ~ /Schemes/ { category = $0; next }
        NF == 1 && $0 ~ /[A-Za-z]/ { amc = $0; next }
        NF >= 8 && (($2 in want) || ($3 in want)) {
            if (category != printed_category) { print ""; print category; print ""; printed_category = category }
            if (amc != printed_amc) { print amc; print ""; printed_amc = amc }
            print
        }' > "$OUT/amfi/$name"
    echo "$OUT/amfi/$name: $(grep -c ';INF' "$OUT/amfi/$name") funds"
done
