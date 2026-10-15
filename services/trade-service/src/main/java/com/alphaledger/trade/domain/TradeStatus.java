package com.alphaledger.trade.domain;

import java.util.EnumSet;
import java.util.Set;

/** Trade lifecycle (FR-TR-3). SETTLED, REJECTED and CANCELLED are terminal. */
public enum TradeStatus {
    NEW,
    VALIDATED,
    SETTLED,
    REJECTED,
    CANCELLED;

    public Set<TradeStatus> allowedNext() {
        return switch (this) {
            case NEW -> EnumSet.of(VALIDATED, REJECTED);
            case VALIDATED -> EnumSet.of(SETTLED, CANCELLED);
            case SETTLED, REJECTED, CANCELLED -> EnumSet.noneOf(TradeStatus.class);
        };
    }

    public boolean canTransitionTo(TradeStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    /** Statuses whose quantity counts towards the current holding. */
    public static final Set<TradeStatus> HOLDING_STATUSES = EnumSet.of(VALIDATED, SETTLED);
}
