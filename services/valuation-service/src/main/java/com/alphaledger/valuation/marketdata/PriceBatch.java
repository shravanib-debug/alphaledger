package com.alphaledger.valuation.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * What one source run produced: the raw input's identity (file name and SHA-256, used for idempotent re-ingestion),
 * the parsed prices, and the rows it could not parse.
 *
 * @param logSource     the source recorded in the ingestion log (a CSV upload may contain rows declaring other sources)
 * @param intradayTicks OFFLINE demo ticks: SYNTHETIC prices for real instruments, accepted only in OFFLINE mode
 */
public record PriceBatch(PriceSourceName logSource, LocalDate dataDate, String fileName, String checksum,
                         List<PriceRecord> records, List<RejectedRow> rejected, boolean rejectUnknownIsins,
                         boolean intradayTicks) {

    /** One parsed price. {@code source} is the price's provenance; {@code fairValueLevel} is set for model marks. */
    public record PriceRecord(int rowNumber, String isin, LocalDate dataDate, Instant asOf, BigDecimal price,
                              PriceSourceName source, String fairValueLevel) {
    }

    public record RejectedRow(int rowNumber, String isin, String reason, String rawRow) {
    }
}
