package com.alphaledger.trade.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/** One status change of a trade. Rows are only ever inserted. */
@Entity
@Immutable
@Table(name = "trade_audit")
public class TradeAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_id", nullable = false)
    private Long tradeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private TradeStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private TradeStatus toStatus;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    private String comment;

    protected TradeAudit() {
    }

    public TradeAudit(Long tradeId, TradeStatus fromStatus, TradeStatus toStatus, Long changedBy, Instant changedAt,
                      String comment) {
        this.tradeId = tradeId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
        this.comment = comment;
    }

    public Long getId() {
        return id;
    }

    public Long getTradeId() {
        return tradeId;
    }

    public TradeStatus getFromStatus() {
        return fromStatus;
    }

    public TradeStatus getToStatus() {
        return toStatus;
    }

    public Long getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public String getComment() {
        return comment;
    }
}
