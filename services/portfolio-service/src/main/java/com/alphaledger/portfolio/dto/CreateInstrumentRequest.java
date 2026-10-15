package com.alphaledger.portfolio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.alphaledger.portfolio.domain.AssetClass;
import com.alphaledger.portfolio.domain.AssetClassProxy;
import com.alphaledger.portfolio.domain.InstrumentType;

/** New instrument. The ISIN check digit is verified unless {@code synthetic} is true (ZZSYN prefix). */
public record CreateInstrumentRequest(
        @NotBlank @Size(min = 12, max = 12) String isin,
        @Size(max = 40) String symbol,
        @Size(max = 10) String exchange,
        @NotBlank @Size(max = 200) String name,
        @NotNull AssetClass assetClass,
        @NotNull AssetClassProxy assetClassProxy,
        @NotNull InstrumentType instrumentType,
        @Size(max = 20) String amfiSchemeCode,
        boolean synthetic) {
}
