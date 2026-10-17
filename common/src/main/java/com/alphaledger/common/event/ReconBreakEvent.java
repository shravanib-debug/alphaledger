package com.alphaledger.common.event;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Payload of {@code recon.break.created} (keyed by run ID) and {@code recon.break.resolved} (keyed by break ID),
 * docs/events.md version 1. {@code status} is the break's status after the change.
 */
public record ReconBreakEvent(
        long breakId,
        long runId,
        String breakType,
        String tradeRef,
        String isin,
        LocalDate tradeDate,
        Long portfolioId,
        BigDecimal internalQuantity,
        BigDecimal externalQuantity,
        BigDecimal internalPrice,
        BigDecimal externalPrice,
        String status,
        String resolutionType,
        Long assignedTo) {
}
