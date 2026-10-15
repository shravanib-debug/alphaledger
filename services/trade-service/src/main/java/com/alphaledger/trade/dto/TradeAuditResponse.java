package com.alphaledger.trade.dto;

import java.time.Instant;

import com.alphaledger.trade.domain.TradeAudit;
import com.alphaledger.trade.domain.TradeStatus;

/** {@code changedBy} is null for system actions such as auto-settlement. */
public record TradeAuditResponse(Long id, TradeStatus fromStatus, TradeStatus toStatus, Long changedBy,
                                 Instant changedAt, String comment) {

    public static TradeAuditResponse from(TradeAudit a) {
        return new TradeAuditResponse(a.getId(), a.getFromStatus(), a.getToStatus(), a.getChangedBy(),
                a.getChangedAt(), a.getComment());
    }
}
