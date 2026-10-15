package com.alphaledger.portfolio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.alphaledger.portfolio.domain.InstrumentStatus;

/** Descriptive fields only: ISIN, type and asset class cannot change once an instrument exists. */
public record UpdateInstrumentRequest(
        @Size(max = 40) String symbol,
        @Size(max = 10) String exchange,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 20) String amfiSchemeCode,
        @NotNull InstrumentStatus status) {
}
