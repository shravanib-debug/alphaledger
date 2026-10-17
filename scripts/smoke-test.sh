#!/usr/bin/env bash
# Smoke test for the local stack: platform health and routing (Phase 1), then login, tokens and role checks
# through the gateway (Phase 2).
# Usage (after `docker compose -f infra/docker-compose.yml up -d --build`):  bash scripts/smoke-test.sh
set -euo pipefail

GATEWAY=${GATEWAY:-http://localhost:8080}
EUREKA=${EUREKA:-http://localhost:8761}
CONFIG=${CONFIG:-http://localhost:8888}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-180}

failures=0

# Retries until the check passes or the timeout expires (Eureka registration takes ~30 s).
check() {
    local name=$1 url=$2 expect=$3
    local deadline=$((SECONDS + TIMEOUT_SECONDS)) body=""
    while (( SECONDS < deadline )); do
        body=$(curl -fsS -H 'Accept: application/json' "$url" 2>/dev/null || true)
        if [[ "$body" == *"$expect"* ]]; then
            echo "PASS  $name"
            return 0
        fi
        sleep 5
    done
    echo "FAIL  $name ($url did not contain '$expect')"
    failures=$((failures + 1))
}

check "discovery-server healthy"              "$EUREKA/actuator/health"            '"status":"UP"'
check "config-server healthy"                 "$CONFIG/actuator/health"            '"status":"UP"'
check "config-server serves shared config"    "$CONFIG/portfolio-service/default"  '"alphaledger.base-currency":"INR"'
check "api-gateway healthy"                   "$GATEWAY/actuator/health"           '"status":"UP"'
check "api-gateway registered in Eureka"      "$EUREKA/eureka/apps/API-GATEWAY"     '"API-GATEWAY"'
check "portfolio-service registered"          "$EUREKA/eureka/apps/PORTFOLIO-SERVICE" '"PORTFOLIO-SERVICE"'
check "request routes through the gateway"    "$GATEWAY/api/v1/ping/portfolio"     '"status":"UP"'

correlation=$(curl -fsS -D - -o /dev/null -H 'X-Correlation-Id: smoke-test-1' "$GATEWAY/api/v1/ping/portfolio" \
    | tr -d '\r' | awk -F': ' 'tolower($1)=="x-correlation-id"{print $2}')
if [[ "$correlation" == "smoke-test-1" ]]; then
    echo "PASS  correlation ID propagated"
else
    echo "FAIL  correlation ID propagated (got '$correlation')"
    failures=$((failures + 1))
fi

# ---------- Phase 2: authentication and authorization through the gateway ----------
DEMO_PASSWORD=${DEMO_USER_PASSWORD:-Demo@AlphaLedger2026}

check "auth-service registered"               "$EUREKA/eureka/apps/AUTH-SERVICE"    '"AUTH-SERVICE"'

# Prints the access token for a demo user (empty if login fails).
login() {
    curl -sS -X POST "$GATEWAY/api/v1/auth/login" -H 'Content-Type: application/json' \
        -d "{\"email\":\"$1\",\"password\":\"$DEMO_PASSWORD\"}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'
}

# expect_status <name> <expected-code> <curl args...>
expect_status() {
    local name=$1 expected=$2; shift 2
    local code
    code=$(curl -sS -o /dev/null -w '%{http_code}' "$@" || true)
    if [[ "$code" == "$expected" ]]; then
        echo "PASS  $name"
    else
        echo "FAIL  $name (expected $expected, got $code)"
        failures=$((failures + 1))
    fi
}

# After a restart the gateway needs up to ~60 s to see auth-service in its Eureka cache; wait for a first login.
deadline=$((SECONDS + TIMEOUT_SECONDS))
until [[ -n "$(login anita.admin@alphaledger.test)" ]] || (( SECONDS >= deadline )); do sleep 5; done

for user in anita.admin priya.pm rohan.ops vikram.auditor; do
    token=$(login "$user@alphaledger.test")
    if [[ -n "$token" ]]; then echo "PASS  login as $user"; else echo "FAIL  login as $user"; failures=$((failures + 1)); fi
done
PM_TOKEN=$(login priya.pm@alphaledger.test)
AUDITOR_TOKEN=$(login vikram.auditor@alphaledger.test)

expect_status "wrong password rejected (401)"      401 -X POST "$GATEWAY/api/v1/auth/login" \
    -H 'Content-Type: application/json' -d '{"email":"priya.pm@alphaledger.test","password":"wrong"}'
expect_status "no token rejected (401)"            401 "$GATEWAY/api/v1/portfolios"
expect_status "invalid token rejected (401)"       401 -H 'Authorization: Bearer not.a.jwt' "$GATEWAY/api/v1/portfolios"
expect_status "auditor reads portfolios (200)"     200 -H "Authorization: Bearer $AUDITOR_TOKEN" "$GATEWAY/api/v1/portfolios"
expect_status "auditor reads instruments (200)"    200 -H "Authorization: Bearer $AUDITOR_TOKEN" "$GATEWAY/api/v1/instruments"
expect_status "auditor cannot create (403)"        403 -X POST -H "Authorization: Bearer $AUDITOR_TOKEN" \
    -H 'Content-Type: application/json' -d '{"name":"Not allowed"}' "$GATEWAY/api/v1/portfolios"
expect_status "spoofed X-User-Roles ignored (403)" 403 -X POST -H "Authorization: Bearer $AUDITOR_TOKEN" \
    -H 'X-User-Roles: ADMIN' -H 'Content-Type: application/json' -d '{"name":"Not allowed"}' "$GATEWAY/api/v1/portfolios"
portfolios=$(curl -fsS -H "Authorization: Bearer $PM_TOKEN" "$GATEWAY/api/v1/portfolios")
if [[ "$portfolios" == *"AlphaLedger India Growth Equity Fund"* ]]; then
    echo "PASS  demo portfolios served through the gateway"
else
    echo "FAIL  demo portfolios served through the gateway"; failures=$((failures + 1))
fi
instruments=$(curl -fsS -H "Authorization: Bearer $PM_TOKEN" "$GATEWAY/api/v1/instruments?isin=INE041025011")
if [[ "$instruments" == *'"symbol":"EMBASSY"'* ]]; then
    echo "PASS  instrument lookup by ISIN"
else
    echo "FAIL  instrument lookup by ISIN"; failures=$((failures + 1))
fi

# ---------- Phase 3: trade capture, outbox and Kafka ----------
check "trade-service registered"              "$EUREKA/eureka/apps/TRADE-SERVICE"   '"TRADE-SERVICE"'

SMOKE_REF="SMOKE-$(date +%s)"
# Trades need a business day; on a weekend use the previous Friday (date in India, the market's time zone).
trade_date=$(TZ=Asia/Kolkata date +%F)
while [[ $(date -d "$trade_date" +%u) -gt 5 ]]; do trade_date=$(date -d "$trade_date - 1 day" +%F); done
trade=$(curl -sS -X POST "$GATEWAY/api/v1/trades" -H "Authorization: Bearer $PM_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"tradeRef\":\"$SMOKE_REF\",\"portfolioId\":2,\"isin\":\"INE041025011\",\"side\":\"BUY\",\"quantity\":500,\"price\":435.00,\"tradeDate\":\"$trade_date\"}")
if [[ "$trade" == *'"status":"VALIDATED"'* ]]; then
    echo "PASS  PM captures a trade through the gateway (VALIDATED)"
else
    echo "FAIL  PM captures a trade through the gateway: $trade"; failures=$((failures + 1))
fi
expect_status "auditor cannot capture trades (403)" 403 -X POST -H "Authorization: Bearer $AUDITOR_TOKEN" \
    -H 'Content-Type: application/json' -d '{"portfolioId":2,"isin":"INE041025011","side":"BUY","quantity":1,"price":1}' \
    "$GATEWAY/api/v1/trades"
expect_status "auditor reads the blotter (200)"     200 -H "Authorization: Bearer $AUDITOR_TOKEN" \
    "$GATEWAY/api/v1/trades?portfolioId=2"

# The outbox relay publishes within about a second; read trade.created from the beginning and look for our trade.
# MSYS_NO_PATHCONV stops Git Bash on Windows from rewriting /opt/... into a Windows path (no effect elsewhere).
if MSYS_NO_PATHCONV=1 docker compose -f "$(dirname "$0")/../infra/docker-compose.yml" exec -T kafka \
        /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic trade.created \
        --from-beginning --timeout-ms 15000 2>/dev/null | grep -q "$SMOKE_REF"; then
    echo "PASS  trade.created event published to Kafka"
else
    echo "FAIL  trade.created event published to Kafka"; failures=$((failures + 1))
fi

# ---------- Phase 4A foundation: holiday calendar and prices ----------
check "valuation-service registered"          "$EUREKA/eureka/apps/VALUATION-SERVICE" '"VALUATION-SERVICE"'

holidays=$(curl -sS -H "Authorization: Bearer $AUDITOR_TOKEN" "$GATEWAY/api/v1/marketdata/holidays?year=2026")
if [[ "$holidays" == *'"description":"Dussehra"'* ]]; then
    echo "PASS  NSE holiday calendar loaded"
else
    echo "FAIL  NSE holiday calendar loaded"; failures=$((failures + 1))
fi
price=$(curl -sS -H "Authorization: Bearer $AUDITOR_TOKEN" "$GATEWAY/api/v1/marketdata/prices/latest?isin=INE041025011")
if [[ "$price" == *'"source":"NSE_BHAVCOPY"'* && "$price" == *'"dataDate":"'* ]]; then
    echo "PASS  real price with source badge (Embassy REIT)"
else
    echo "FAIL  real price with source badge: $price"; failures=$((failures + 1))
fi
synthetic=$(curl -sS -H "Authorization: Bearer $AUDITOR_TOKEN" "$GATEWAY/api/v1/marketdata/prices/latest?isin=ZZSYN0000001")
if [[ "$synthetic" == *'"source":"SYNTHETIC"'* && "$synthetic" == *'"fairValueLevel":"L3"'* ]]; then
    echo "PASS  synthetic instrument priced by an L3 mark"
else
    echo "FAIL  synthetic instrument priced by an L3 mark: $synthetic"; failures=$((failures + 1))
fi
upload_file="smoke-prices-$$.csv"   # relative path: native Windows curl cannot read Git Bash /tmp paths
printf 'isin,data_date,price\nINE002A01018,2026-10-14,1174.60\n' > "$upload_file"
expect_status "PM cannot upload prices (403)"   403 -X POST -H "Authorization: Bearer $PM_TOKEN" \
    -F "file=@$upload_file;filename=prices.csv" "$GATEWAY/api/v1/marketdata/prices/upload"
rm -f "$upload_file"

# ---------- Phase 4: positions and valuation (event chain trade -> position -> valuation) ----------
check "position-service registered"           "$EUREKA/eureka/apps/POSITION-SERVICE" '"POSITION-SERVICE"'

# The smoke trade above (500 Embassy REIT in portfolio 2) must reach positions and then valuation via Kafka.
await_json() {
    local name=$1 url=$2 expect=$3
    local deadline=$((SECONDS + TIMEOUT_SECONDS)) body=""
    while (( SECONDS < deadline )); do
        body=$(curl -sS -H "Authorization: Bearer $AUDITOR_TOKEN" "$url" 2>/dev/null || true)
        if [[ "$body" == *"$expect"* ]]; then
            echo "PASS  $name"
            return 0
        fi
        sleep 3
    done
    echo "FAIL  $name ($url did not contain '$expect')"
    failures=$((failures + 1))
}
await_json "trade became a position (Kafka -> position-service)" \
    "$GATEWAY/api/v1/portfolios/2/positions" '"isin":"INE041025011"'
await_json "position reached valuation (Kafka -> valuation-service)" \
    "$GATEWAY/api/v1/portfolios/2/valuation" '"priceSource":"NSE_BHAVCOPY"'
valuation=$(curl -sS -H "Authorization: Bearer $AUDITOR_TOKEN" "$GATEWAY/api/v1/portfolios/2/valuation")
if [[ "$valuation" == *'"valuationBasis":"MARKET_VALUE_ONLY"'* && "$valuation" == *'"nav":null'* ]]; then
    echo "PASS  valuation labelled Market Value, not NAV"
else
    echo "FAIL  valuation labelled Market Value, not NAV"; failures=$((failures + 1))
fi
expect_status "exposure by asset class (200)"   200 -H "Authorization: Bearer $AUDITOR_TOKEN" \
    "$GATEWAY/api/v1/portfolios/2/exposure"
expect_status "PM cannot set limit rules (403)" 403 -X POST -H "Authorization: Bearer $PM_TOKEN" \
    -H 'Content-Type: application/json' -d '{"assetClass":"CREDIT","maxPct":50}' "$GATEWAY/api/v1/valuations/limit-rules"

# ---------- Phase 5: reconciliation ----------
check "reconciliation-service registered"     "$EUREKA/eureka/apps/RECONCILIATION-SERVICE" '"RECONCILIATION-SERVICE"'
OPS_TOKEN=$(login rohan.ops@alphaledger.test)
recon_file="smoke-recon-$$.csv"
printf 'trade_ref,isin,trade_date,settle_date,side,quantity,price
SMOKE-CUST-%s,INE002A01018,2026-10-13,2026-10-14,BUY,10,1174.60
' "$$" > "$recon_file"
recon=$(curl -sS -X POST -H "Authorization: Bearer $OPS_TOKEN" -F "file=@$recon_file;filename=custodian.csv"     "$GATEWAY/api/v1/recon/runs?runDate=2026-10-14" || true)
if [[ "$recon" == *'"status":"COMPLETED"'* && "$recon" == *'"missing_internal":1'* ]]; then
    echo "PASS  custodian upload reconciled (unknown trade -> MISSING_INTERNAL break)"
else
    echo "FAIL  custodian upload reconciled: $recon"; failures=$((failures + 1))
fi
expect_status "PM cannot upload custodian files (403)" 403 -X POST -H "Authorization: Bearer $PM_TOKEN"     -F "file=@$recon_file;filename=custodian.csv" "$GATEWAY/api/v1/recon/runs?runDate=2026-10-14"
rm -f "$recon_file"
expect_status "auditor can read breaks (200)"   200 -H "Authorization: Bearer $AUDITOR_TOKEN"     "$GATEWAY/api/v1/recon/breaks"

if (( failures > 0 )); then
    echo "$failures check(s) failed"
    exit 1
fi
echo "All checks passed"
