package com.alphaledger.valuation.marketdata;

/** A source has no file for the requested date. The run is logged as FAILED and no price is written. */
public class PriceFileUnavailableException extends IllegalStateException {

    public PriceFileUnavailableException(String message) {
        super(message);
    }
}
