package com.alphaledger.valuation.marketdata;

import java.time.LocalDate;
import java.util.Collection;

/**
 * A pluggable producer of prices (architecture.md §5.3). Implementations: {@link SyntheticPriceSource} now;
 * NseBhavcopyPriceSource, AmfiNavPriceSource and MfApiPriceSource with the deferred part of Phase 4A.
 * Selection and fallback order come from configuration ({@link PriceSourceRegistry}).
 */
public interface PriceSource {

    PriceSourceName name();

    /** Whether this source may price the instrument (e.g. by instrument type, or synthetic-only). */
    boolean supports(InstrumentRef instrument);

    /** Fetches prices for {@code dataDate} for the given (already supported) instruments. */
    PriceBatch fetch(LocalDate dataDate, Collection<InstrumentRef> instruments);
}
