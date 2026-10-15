package com.alphaledger.trade.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.alphaledger.common.error.BusinessRuleException;

/** A captured trade. Status changes only through the lifecycle methods, which enforce {@link TradeStatus}. */
@Entity
@Table(name = "trade")
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_ref", nullable = false, unique = true, updatable = false)
    private String tradeRef;

    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private Long portfolioId;

    @Column(name = "instrument_id", updatable = false)
    private Long instrumentId;

    @Column(nullable = false, updatable = false)
    private String isin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TradeSide side;

    @Column(nullable = false, updatable = false)
    private BigDecimal quantity;

    @Column(nullable = false, updatable = false)
    private BigDecimal price;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "trade_date", nullable = false, updatable = false)
    private LocalDate tradeDate;

    @Column(name = "settle_date", nullable = false, updatable = false)
    private LocalDate settleDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TradeStatus status;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Trade() {
    }

    public static Trade capture(String tradeRef, Long portfolioId, Long instrumentId, String isin, TradeSide side,
                                BigDecimal quantity, BigDecimal price, LocalDate tradeDate, LocalDate settleDate,
                                Long createdBy, Instant now) {
        Trade trade = new Trade();
        trade.tradeRef = tradeRef;
        trade.portfolioId = portfolioId;
        trade.instrumentId = instrumentId;
        trade.isin = isin;
        trade.side = side;
        trade.quantity = quantity;
        trade.price = price;
        trade.currency = "INR";
        trade.tradeDate = tradeDate;
        trade.settleDate = settleDate;
        trade.createdBy = createdBy;
        trade.status = TradeStatus.NEW;
        trade.createdAt = now;
        trade.updatedAt = now;
        return trade;
    }

    public void validate(Instant now) {
        moveTo(TradeStatus.VALIDATED, now);
    }

    public void reject(String reason, Instant now) {
        moveTo(TradeStatus.REJECTED, now);
        this.rejectionReason = reason;
    }

    public void settle(Instant now) {
        moveTo(TradeStatus.SETTLED, now);
    }

    public void cancel(Instant now) {
        moveTo(TradeStatus.CANCELLED, now);
    }

    private void moveTo(TradeStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new BusinessRuleException("Trade " + tradeRef + " cannot move from " + status + " to " + next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    /** Quantity with sign: positive for BUY, negative for SELL. */
    public BigDecimal signedQuantity() {
        return side == TradeSide.BUY ? quantity : quantity.negate();
    }

    public Long getId() {
        return id;
    }

    public String getTradeRef() {
        return tradeRef;
    }

    public Long getPortfolioId() {
        return portfolioId;
    }

    public Long getInstrumentId() {
        return instrumentId;
    }

    public String getIsin() {
        return isin;
    }

    public TradeSide getSide() {
        return side;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public LocalDate getSettleDate() {
        return settleDate;
    }

    public TradeStatus getStatus() {
        return status;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
