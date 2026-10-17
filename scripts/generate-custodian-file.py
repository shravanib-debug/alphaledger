#!/usr/bin/env python3
"""Builds a demo custodian file from the trades settled on RUN_DATE, with exactly 3 missing-internal,
3 missing-external, 4 quantity and 4 price breaks planted (the Phase 5 demo). Synthetic data only.

The choice of which trades to break is deterministic (sorted by tradeRef), so the same book gives the same file.
Usage (stack running, demo trades booked and settled):
    python scripts/generate-custodian-file.py [--run-date 2026-10-15] [--out custodian-2026-10-15.csv]
"""
import argparse
import json
import os
import sys
import urllib.parse
import urllib.request
from decimal import Decimal

HEADER = "trade_ref,isin,trade_date,settle_date,side,quantity,price"


def call(gateway, path, token=None, body=None):
    req = urllib.request.Request(gateway + path, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req) as resp:
        return json.load(resp)


def settled_trades(gateway, token, run_date):
    trades, page = [], 0
    while True:
        query = urllib.parse.urlencode({"status": "SETTLED", "page": page, "size": 200})
        result = call(gateway, "/api/v1/trades?" + query, token)
        trades += [t for t in result["content"] if t["settleDate"] == run_date]
        page += 1
        if page >= result["totalPages"]:
            return sorted(trades, key=lambda t: t["tradeRef"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-date", default="2026-10-15")
    parser.add_argument("--out")
    parser.add_argument("--gateway", default=os.environ.get("GATEWAY", "http://localhost:8080"))
    args = parser.parse_args()
    out = args.out or f"custodian-{args.run_date}.csv"

    password = os.environ.get("DEMO_USER_PASSWORD", "Demo@AlphaLedger2026")
    token = call(args.gateway, "/api/v1/auth/login",
                 body={"email": "rohan.ops@alphaledger.test", "password": password})["accessToken"]
    trades = settled_trades(args.gateway, token, args.run_date)
    if len(trades) < 11:
        sys.exit(f"Need at least 11 trades settled on {args.run_date}, found {len(trades)}; run demo-trades.sh first")

    rows = [HEADER]
    for i, t in enumerate(trades):
        if i < 3:
            continue                                            # MISSING_EXTERNAL: custodian never reports these
        quantity, price = Decimal(str(t["quantity"])), Decimal(str(t["price"]))
        if i < 7:
            quantity += 1                                       # QTY_MISMATCH
        elif i < 11:
            price += Decimal("2.50")                            # PRICE_MISMATCH (well outside the 0.01 tolerance)
        rows.append(f"{t['tradeRef']},{t['isin']},{t['tradeDate']},{args.run_date},{t['side']},"
                    f"{quantity.normalize():f},{price.normalize():f}")
    template = trades[-1]
    for n in range(1, 4):                                       # MISSING_INTERNAL: unknown to AlphaLedger
        rows.append(f"CUST-ONLY-{n},{template['isin']},{template['tradeDate']},{args.run_date},BUY,10,"
                    f"{Decimal(str(template['price'])).normalize():f}")

    with open(out, "w", newline="\n") as f:
        f.write("\n".join(rows) + "\n")
    print(f"Wrote {out}: {len(trades)} internal trades, breaks planted 3 missing-internal, 3 missing-external, "
          f"4 quantity, 4 price")


if __name__ == "__main__":
    main()
