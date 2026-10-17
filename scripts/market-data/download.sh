#!/usr/bin/env bash
# Downloads public end-of-day price files into the local cache (data/market-cache/, git-ignored), politely:
# one request per file, a pause between requests, and nothing fetched twice (cached files are reused).
# Personal / portfolio use only; raw files are never committed (see README, "Data sources and terms").
#
# NSE's terms of use prohibit systematic or automated data collection. Do not schedule this script for NSE files:
# download bhavcopies in a browser (nseindia.com > All Reports > Capital Market > "CM - UDiFF Common Bhavcopy Final")
# and save the zip in data/market-cache/NSE_BHAVCOPY/<yyyy>/. The NSE part below runs only for a deliberate, one-off,
# small manual fetch, and only with NSE_MANUAL_FETCH=yes.
#   AMFI NAVAll.txt (latest): bash scripts/market-data/download.sh --amfi
#   NSE, one-off:             NSE_MANUAL_FETCH=yes bash scripts/market-data/download.sh 2026-10-14
set -euo pipefail

CACHE=${MARKET_CACHE_DIR:-data/market-cache}
UA="Mozilla/5.0 (AlphaLedger portfolio project; personal use)"
NSE_URL="https://nsearchives.nseindia.com/content/cm"
AMFI_URL="https://www.amfiindia.com/spages/NAVAll.txt"

download_nse() {
    local date=$1 compact=${1//-/}
    if [[ "${NSE_MANUAL_FETCH:-}" != "yes" ]]; then
        echo "skip    $date: NSE terms prohibit automated collection; save the bhavcopy from a browser into"              "$CACHE/NSE_BHAVCOPY/${date:0:4}/ (or set NSE_MANUAL_FETCH=yes for a one-off manual fetch)" >&2
        return
    fi
    local file="BhavCopy_NSE_CM_0_0_0_${compact}_F_0000.csv.zip"
    local dir="$CACHE/NSE_BHAVCOPY/${date:0:4}"
    mkdir -p "$dir"
    if [[ -s "$dir/$file" ]]; then
        echo "cached  $dir/$file"
        return
    fi
    local code
    code=$(curl -sS -A "$UA" -o "$dir/$file.part" -w '%{http_code}' "$NSE_URL/$file" || true)
    if [[ "$code" == "200" ]]; then
        mv "$dir/$file.part" "$dir/$file"
        echo "fetched $dir/$file"
    else
        rm -f "$dir/$file.part"
        echo "skip    $date: HTTP $code (holiday, weekend, not yet published, or blocked)" >&2
    fi
    sleep 2
}

download_amfi() {
    local tmp="$CACHE/AMFI_NAV/NAVAll.part"
    mkdir -p "$CACHE/AMFI_NAV"
    curl -sSL -A "$UA" -o "$tmp" "$AMFI_URL"
    # Name the file after the NAV date it contains (the most common date in the file).
    local nav_date
    nav_date=$(awk -F';' 'NF >= 6 && $NF ~ /^[0-9]{2}-[A-Za-z]{3}-[0-9]{4}/ {print $NF}' "$tmp" | tr -d '\r' \
        | sort | uniq -c | sort -rn | head -1 | awk '{print $2}')
    local iso
    iso=$(date -d "$nav_date" +%Y-%m-%d)
    mkdir -p "$CACHE/AMFI_NAV/${iso:0:4}"
    mv "$tmp" "$CACHE/AMFI_NAV/${iso:0:4}/NAVAll_$iso.txt"
    echo "fetched $CACHE/AMFI_NAV/${iso:0:4}/NAVAll_$iso.txt"
}

if [[ $# -eq 0 ]]; then
    echo "usage: $0 [--amfi] [yyyy-mm-dd ...]" >&2
    exit 1
fi
for arg in "$@"; do
    if [[ "$arg" == "--amfi" ]]; then
        download_amfi
    else
        download_nse "$arg"
    fi
done
