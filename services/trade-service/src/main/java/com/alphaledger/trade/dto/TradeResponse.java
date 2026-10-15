package com.alphaledger.trade.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.alphaledger.trade.domain.Trade;
import com.alphaledger.trade.domain.TradeSide;
import com.alphaledger.trade.domain.TradeStatus;

public record TradeResponse(
        Long id,
        String tradeRef,
        Long portfolioId,
        Long instrumentId,
        String isin,
        TradeSide side,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal notional,
        String currency,
        LocalDate tradeDate,
        LocalDate settleDate,
        TradeStatus status,
        String rejectionReason,
        Long createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static TradeResponse from(Trade t) {
        return new TradeResponse(t.getId(), t.getTradeRef(), t.getPortfolioId(), t.getInstrumentId(), t.getIsin(),
                t.getSide(), t.getQuantity(), t.getPrice(), t.getQuantity().multiply(t.getPrice()), t.getCurrency(),
                t.getTradeDate(), t.getSettleDate(), t.getStatus(), t.getRejectionReason(), t.getCreatedBy(),
                t.getCreatedAt(), t.getUpdatedAt());
    }
}
