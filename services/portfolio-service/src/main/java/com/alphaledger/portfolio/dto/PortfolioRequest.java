package com.alphaledger.portfolio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Create or update a portfolio. {@code managerId} is required when an admin acts; a PM always manages
 * their own portfolios. Base currency is INR in v1 (single-currency).
 */
public record PortfolioRequest(
        @NotBlank @Size(max = 120) String name,
        @Positive Long managerId,
        @Pattern(regexp = "INR", message = "must be INR (single base currency in v1)") String baseCurrency) {

    public String baseCurrencyOrDefault() {
        return baseCurrency == null ? "INR" : baseCurrency;
    }
}
