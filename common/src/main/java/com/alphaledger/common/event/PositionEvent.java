package com.alphaledger.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Payload of {@code position.updated} (docs/events.md, version 1): the full state of one holding after the change,
 * so consumers can simply replace their copy. A closed position has quantity 0.
 */
public record PositionEvent(
        long portfolioId,
        long instrumentId,
        String isin,
        BigDecimal quantity,
        BigDecimal avgCost,
        BigDecimal realizedPnl,
        long lastTradeId,
        Instant updatedAt) {
}
