package com.alphaledger.portfolio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.alphaledger.portfolio.domain.AssetClass;

public record FundRequest(@NotBlank @Size(max = 120) String name, @NotNull AssetClass assetClass) {
}
