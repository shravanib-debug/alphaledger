package com.alphaledger.common.event;

/** Kafka topic names (architecture.md §4.1). Messages on trade topics are keyed by portfolio ID. */
public final class Topics {

    public static final String TRADE_CREATED = "trade.created";
    public static final String TRADE_SETTLED = "trade.settled";
    public static final String TRADE_CANCELLED = "trade.cancelled";

    private Topics() {
    }
}
