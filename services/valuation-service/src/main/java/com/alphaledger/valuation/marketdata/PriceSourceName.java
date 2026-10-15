package com.alphaledger.valuation.marketdata;

/** Where a price came from; stored with every price and shown as the source badge (architecture.md §5.3). */
public enum PriceSourceName {
    NSE_BHAVCOPY,
    AMFI_NAV,
    MFAPI,
    SYNTHETIC,
    CSV_UPLOAD,
    YFINANCE;

    /** Sources a CSV row may declare as the price's real provenance (manual upload or the committed seed file). */
    public boolean allowedInCsv() {
        return this != SYNTHETIC && this != MFAPI;
    }
}
