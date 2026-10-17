package com.alphaledger.valuation.marketdata;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;

import org.springframework.stereotype.Component;

/** Primary source for listed instruments: equities, REITs, InvITs, G-Secs and bonds (architecture.md §5.3). */
@Component
public class NseBhavcopyPriceSource implements PriceSource {

    static final Set<String> TYPES = Set.of("EQUITY", "REIT", "INVIT", "GSEC", "BOND");

    private final MarketDataFiles files;

    public NseBhavcopyPriceSource(MarketDataFiles files) {
        this.files = files;
    }

    @Override
    public PriceSourceName name() {
        return PriceSourceName.NSE_BHAVCOPY;
    }

    @Override
    public boolean supports(InstrumentRef instrument) {
        return !instrument.synthetic() && TYPES.contains(instrument.instrumentType());
    }

    /** The whole day's file is parsed; the pipeline keeps only instruments we hold. */
    @Override
    public PriceBatch fetch(LocalDate dataDate, Collection<InstrumentRef> instruments) {
        MarketDataFiles.RawFile file = files.nseBhavcopy(dataDate).orElseThrow(() ->
                new PriceFileUnavailableException("No NSE bhavcopy for " + dataDate
                        + " (holiday, weekend, not yet published, or not in the offline sample)"));
        return NseBhavcopyParser.parse(file.name(), file.content(), dataDate);
    }
}
