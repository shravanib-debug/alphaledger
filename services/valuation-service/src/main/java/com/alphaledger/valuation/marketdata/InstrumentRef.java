package com.alphaledger.valuation.marketdata;

/** valuation-service's read copy of an instrument (same ID as in portfolio-service). */
public record InstrumentRef(long instrumentId, String isin, String symbol, String name, String instrumentType,
                            String assetClass, boolean synthetic, String amfiSchemeCode) {

    public InstrumentRef(long instrumentId, String isin, String symbol, String name, String instrumentType,
                         String assetClass, boolean synthetic) {
        this(instrumentId, isin, symbol, name, instrumentType, assetClass, synthetic, null);
    }
}
