package com.alphaledger.common.event;

/** Kafka topic names (architecture.md §4.1). Messages are keyed by portfolio ID, except price.updated (instrument ID). */
public final class Topics {

    public static final String TRADE_CREATED = "trade.created";
    public static final String TRADE_SETTLED = "trade.settled";
    public static final String TRADE_CANCELLED = "trade.cancelled";
    public static final String POSITION_UPDATED = "position.updated";
    public static final String PRICE_UPDATED = "price.updated";
    public static final String VALUATION_CALCULATED = "valuation.calculated";
    public static final String LIMIT_BREACHED = "limit.breached";

    private Topics() {
    }
}
