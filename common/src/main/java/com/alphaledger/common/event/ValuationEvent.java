package com.alphaledger.common.event;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Payload of {@code valuation.calculated} (docs/events.md, version 1). In v1 {@code valuationBasis} is
 * MARKET_VALUE_ONLY and {@code nav} is null: market value is not NAV (prd.md business rule 2).
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
