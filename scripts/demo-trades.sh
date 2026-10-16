#!/usr/bin/env bash
# Books a small demo book through the gateway as Priya (PM): 20 trades on 14-Oct-2026 at that day's real closes
# (reference-data/sample/prices/seed_closes_2026-10-14.csv), across the three demo portfolios.
# Trade references are fixed (DEMO-01 ...), so running the script again changes nothing (idempotent tradeRef).
# Usage (stack running):  bash scripts/demo-trades.sh
set -euo pipefail

GATEWAY=${GATEWAY:-http://localhost:8080}
PASSWORD=${DEMO_USER_PASSWORD:-Demo@AlphaLedger2026}
TRADE_DATE=2026-10-14

TOKEN=$(curl -sS -X POST "$GATEWAY/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"priya.pm@alphaledger.test\",\"password\":\"$PASSWORD\"}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
if [[ -z "$TOKEN" ]]; then
    echo "Login failed; is the stack up?" >&2
    exit 1
fi

# ref portfolio isin side quantity price
TRADES="
DEMO-01 1 IN0020260041 BUY 10000 97.60
DEMO-02 1 IN0020260033 BUY 5000 102.49
DEMO-03 1 INE053F07579 BUY 200 1024.75
DEMO-04 1 INE906B07DF8 BUY 150 1116.00
DEMO-05 1 INF179K01XD8 BUY 50000 35.0155
DEMO-06 1 INF209K01YN0 BUY 2000 403.3853
DEMO-07 2 INE041025011 BUY 500 434.91
DEMO-08 2 INE0CCU25019 BUY 300 495.02
DEMO-09 2 INE0FDU25010 BUY 600 322.24
DEMO-10 2 INE219X23014 BUY 1000 168.29
DEMO-11 2 INE0GGX23010 BUY 1500 103.84
DEMO-12 2 INE041025011 SELL 100 434.91
DEMO-13 3 INE002A01018 BUY 100 1174.60
DEMO-14 3 INE040A01034 BUY 200 710.70
DEMO-15 3 INE009A01021 BUY 150 1088.00
DEMO-16 3 INE467B01029 BUY 50 2304.90
DEMO-17 3 INF879O01027 BUY 3000 89.3598
DEMO-18 3 INF789F01XA0 BUY 1000 159.31
DEMO-19 3 ZZSYN0000001 BUY 20 1360.00
DEMO-20 3 INE009A01021 SELL 50 1088.00
"

booked=0
while read -r ref portfolio isin side quantity price; do
    [[ -z "$ref" ]] && continue
    status=$(curl -sS -X POST "$GATEWAY/api/v1/trades" -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' \
        -d "{\"tradeRef\":\"$ref\",\"portfolioId\":$portfolio,\"isin\":\"$isin\",\"side\":\"$side\",\"quantity\":$quantity,\"price\":$price,\"tradeDate\":\"$TRADE_DATE\"}" \
        | sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p')
    printf '%-8s portfolio %s %-4s %8s %-12s @ %-9s %s\n' "$ref" "$portfolio" "$side" "$quantity" "$isin" "$price" "${status:-ERROR}"
    [[ "$status" == "VALIDATED" ]] && booked=$((booked + 1))
done <<< "$TRADES"

echo "$booked trades VALIDATED (already-booked trades show their stored status)."
echo "Positions and valuations update within a few seconds, e.g.:"
echo "  curl -H \"Authorization: Bearer <token>\" $GATEWAY/api/v1/portfolios/2/valuation"
