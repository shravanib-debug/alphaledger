package com.alphaledger.trade.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.alphaledger.trade.domain.TradeSide;

/**
 * New trade (FR-TR-1). {@code tradeRef} is optional: when supplied it makes the request idempotent (FR-TR-9).
 * {@code tradeDate} defaults to today (market time zone); {@code settleDate} defaults to T+1 business day.
 */
public record CreateTradeRequest(
        @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "may contain letters, digits, '.', '_' and '-'")
        String tradeRef,
        @NotNull @Positive Long portfolioId,
        @NotBlank @Size(min = 12, max = 12) String isin,
        @NotNull TradeSide side,
        @NotNull @Positive @Digits(integer = 16, fraction = 4) BigDecimal quantity,
        @NotNull @Positive @Digits(integer = 14, fraction = 6) BigDecimal price,
        LocalDate tradeDate,
        LocalDate settleDate) {
}
