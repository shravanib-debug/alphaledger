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

Consumers (from Phase 4): position-service (`trade.created`, `trade.cancelled`), reconciliation-service (`trade.settled`), valuation-service (`trade.settled`, v1.5 cash).
