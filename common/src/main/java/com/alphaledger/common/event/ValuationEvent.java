package com.alphaledger.common.event;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Payload of {@code valuation.calculated} (docs/events.md, version 1). From v1.5 {@code valuationBasis} is FULL_NAV
 * and {@code nav} = market value + cash - liabilities; with MARKET_VALUE_ONLY (v1) {@code nav} is null.
 */
public record ValuationEvent(
        long portfolioId,
        LocalDate asOfDate,
        BigDecimal marketValue,
        BigDecimal totalCost,
        BigDecimal unrealizedPnl,
        BigDecimal realizedPnl,
        BigDecimal nav,
        String valuationBasis,
        int stalePositions) {
}
