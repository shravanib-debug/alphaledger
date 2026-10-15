package com.alphaledger.portfolio.dto;

import com.alphaledger.portfolio.domain.AssetClass;
import com.alphaledger.portfolio.domain.AssetClassProxy;
import com.alphaledger.portfolio.domain.Instrument;
import com.alphaledger.portfolio.domain.InstrumentStatus;
import com.alphaledger.portfolio.domain.InstrumentType;

public record InstrumentResponse(
        Long id,
        String isin,
        String symbol,
        String exchange,
        String name,
        AssetClass assetClass,
        AssetClassProxy assetClassProxy,
        InstrumentType instrumentType,
        String currency,
        String amfiSchemeCode,
        boolean synthetic,
        InstrumentStatus status) {

    public static InstrumentResponse from(Instrument i) {
        return new InstrumentResponse(i.getId(), i.getIsin(), i.getSymbol(), i.getExchange(), i.getName(),
                i.getAssetClass(), i.getAssetClassProxy(), i.getInstrumentType(), i.getCurrency(),
                i.getAmfiSchemeCode(), i.isSynthetic(), i.getStatus());
    }
}
