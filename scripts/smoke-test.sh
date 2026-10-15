#!/usr/bin/env bash
# Phase 1 smoke test: checks that the local stack is up and that a request routes through the gateway.
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

if (( failures > 0 )); then
    echo "$failures check(s) failed"
    exit 1
fi
echo "All checks passed"
