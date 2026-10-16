package com.alphaledger.common.event;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Payload of {@code limit.breached} (docs/events.md, version 1): exposure to an asset class above its limit. */
public record LimitBreachEvent(
        long portfolioId,
        String assetClass,
        BigDecimal exposurePct,
        BigDecimal maxPct,
        BigDecimal marketValue,
        LocalDate asOfDate) {
}
