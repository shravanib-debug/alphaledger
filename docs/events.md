# AlphaLedger: Event Schemas

> Kafka events between services. Topic list, keys and retention: `architecture.md` §4.1. Java types live in
> `common` (`com.alphaledger.common.event`), so producers and consumers share one definition.

## Envelope (all events)

Every message value is a JSON envelope; the payload depends on `eventType`.

| Field | Type | Notes |
|---|---|---|
| `eventId` | UUID | Unique per event; consumers deduplicate on it (`processed_event`). |
| `eventType` | string | UPPER_SNAKE of the topic, e.g. `TRADE_CREATED`. |
| `eventVersion` | int | Starts at 1. Only additive, optional changes within a version. |
| `occurredAt` | ISO-8601 instant (UTC) | When the change was committed. |
| `correlationId` | string | Correlation ID of the request that caused the event; also sent as the `X-Correlation-Id` record header. |
| `producer` | string | Service name, e.g. `trade-service`. |
| `payload` | object | Event-specific, below. |

Delivery is **at least once** (transactional outbox + relay), so consumers must be idempotent. Messages on trade topics are keyed by `portfolioId`, so events for one portfolio arrive in order.

## Trade events (producer: trade-service)

| Topic | `eventType` | Emitted when | `payload.status` |
|---|---|---|---|
| `trade.created` | `TRADE_CREATED` | A trade passes validation (NEW → VALIDATED). Rejected trades emit nothing. | `VALIDATED` |
| `trade.settled` | `TRADE_SETTLED` | Auto-settlement on the settle date, or manual settle by an Admin. | `SETTLED` |
| `trade.cancelled` | `TRADE_CANCELLED` | A VALIDATED trade is cancelled before settlement. | `CANCELLED` |

Payload (`TradeEvent`, version 1):

| Field | Type | Notes |
|---|---|---|
| `tradeId` | long | trade-service ID. |
| `tradeRef` | string | Business reference; part of the reconciliation match key. |
| `portfolioId` | long | Also the message key. |
| `instrumentId` | long | Same ID in every service (instrument master). |
| `isin` | string | Fallback join key; used for reconciliation matching. |
| `side` | `BUY` \| `SELL` | |
| `quantity` | decimal | Up to 4 decimal places (mutual fund units). |
| `price` | decimal | INR, up to 6 decimal places. |
| `currency` | string | `INR` in v1. |
| `tradeDate` | ISO date | Trading day (market time zone, Asia/Kolkata). |
| `settleDate` | ISO date | Defaults to T+1 business day. |
| `status` | string | Trade status after the change (see table above). |

Example (`trade.created`, key `"2"`):

```json
{
  "eventId": "8b1f6f3e-6c55-4c63-9a2e-5d6f2d7a9c10",
  "eventType": "TRADE_CREATED",
  "eventVersion": 1,
  "occurredAt": "2026-10-19T05:30:00Z",
  "correlationId": "7d0c4c4e-2b8a-4f43-a3b1-0c1d2e3f4a5b",
  "producer": "trade-service",
  "payload": {
    "tradeId": 1001,
    "tradeRef": "TRD-20261019-0001",
    "portfolioId": 2,
    "instrumentId": 1,
    "isin": "INE041025011",
    "side": "BUY",
    "quantity": 500,
    "price": 435.00,
    "currency": "INR",
    "tradeDate": "2026-10-19",
    "settleDate": "2026-10-21",
    "status": "VALIDATED"
  }
}
```

Consumers: position-service (`trade.created`, `trade.cancelled`); from Phase 5 reconciliation-service (`trade.settled`); from Phase 4B valuation-service (`trade.settled`, cash; idempotent by `eventId` and by `tradeRef`).

## Position events (producer: position-service)

`position.updated` / `POSITION_UPDATED`, keyed by `portfolioId`. Emitted after every applied trade event. The payload is
the **full state** of one holding, so consumers replace their copy rather than apply a delta (a closed position has
quantity 0).

| Field | Type | Notes |
|---|---|---|
| `portfolioId`, `instrumentId`, `isin` | long, long, string | |
| `quantity` | decimal | Units held after the change. |
| `avgCost` | decimal | Weighted-average cost (6 decimal places); 0 when closed. |
| `realizedPnl` | decimal | Cumulative realized P&L in INR. |
| `lastTradeId` | long | Latest trade applied to this holding. |
| `updatedAt` | instant | Consumers ignore an update older than the one they hold. |

Consumer: valuation-service.

## Valuation events (producer: valuation-service)

| Topic | `eventType` | Key | Emitted when | Payload |
|---|---|---|---|---|
| `price.updated` | `PRICE_UPDATED` | `instrumentId` | A price is loaded (seed, upload, synthetic marks or ticks, daily run) | `instrumentId`, `isin`, `price`, `currency`, `dataDate`, `asOf`, `source` |
| `valuation.calculated` | `VALUATION_CALCULATED` | `portfolioId` | The end-of-day run writes a portfolio's snapshot | `portfolioId`, `asOfDate`, `marketValue`, `totalCost`, `unrealizedPnl`, `realizedPnl`, `nav` (market value + cash − liabilities; null only under the v1 basis), `valuationBasis` (`FULL_NAV` from v1.5; `MARKET_VALUE_ONLY` in v1), `stalePositions` |
| `limit.breached` | `LIMIT_BREACHED` | `portfolioId` | An asset class's share of market value exceeds its limit (once per portfolio, asset class and day) | `portfolioId`, `assetClass`, `exposurePct`, `maxPct`, `marketValue`, `asOfDate` |

Consumers: notification-service (`limit.breached`, Phase 6); planned: analytics (`valuation.calculated`, Phase 7).

## Reconciliation events (producer: reconciliation-service)

| Topic | `eventType` | Key | Emitted when | Payload |
|---|---|---|---|---|
| `recon.break.created` | `RECON_BREAK_CREATED` | `runId` | A run finds a break (one event per break) | `breakId`, `runId`, `breakType`, `tradeRef`, `isin`, `tradeDate`, `portfolioId` (null for `MISSING_INTERNAL`), `internalQuantity`, `externalQuantity`, `internalPrice`, `externalPrice`, `status`, `resolutionType` (null), `assignedTo` |
| `recon.break.resolved` | `RECON_BREAK_RESOLVED` | `breakId` | A break is resolved | Same payload, with `status` `RESOLVED` and `resolutionType` set |

Quantities are signed (SELL negative). reconciliation-service consumes `trade.settled` (group `reconciliation-service`, idempotent by `eventId`). Consumers: notification-service (`recon.break.created` and `recon.break.resolved`, group `notification-service`, idempotent by `eventId`); planned: analytics (both, Phase 7).

## Dead-letter topics

Every consumed topic has `<topic>.DLT` (3 partitions), created on startup by any consuming service. A record that still fails after the retries, or cannot be read, is published there with the same key and value plus Spring Kafka's `kafka_dlt-original-topic`, `kafka_dlt-exception-message` and related headers. A replay (`POST /api/v1/ops/dead-letters/{topic}/replay`, Admin) republishes it to the original topic without the `kafka_dlt-*` headers and with `alphaledger-replayed-from`; consumers are idempotent, so a replayed event that had in fact been processed is skipped.
