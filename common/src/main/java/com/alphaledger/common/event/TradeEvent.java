package com.alphaledger.common.event;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Payload of {@code trade.created}, {@code trade.settled} and {@code trade.cancelled} (docs/events.md, version 1).
 * {@code status} is the trade's status after the change. Consumers identify the instrument by
 * {@code instrumentId}; {@code isin} is the fallback join key.
 */
public record TradeEvent(
        long tradeId,
        String tradeRef,
        long portfolioId,
        long instrumentId,
        String isin,
        String side,
        BigDecimal quantity,
        BigDecimal price,
        String currency,
        LocalDate tradeDate,
        LocalDate settleDate,
        String status) {
}
