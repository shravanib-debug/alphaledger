package com.alphaledger.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Payload of {@code price.updated} (docs/events.md, version 1), keyed by instrument ID. */
public record PriceEvent(
        long instrumentId,
        String isin,
        BigDecimal price,
        String currency,
        LocalDate dataDate,
        Instant asOf,
        String source) {
}
