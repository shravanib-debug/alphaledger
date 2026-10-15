package com.alphaledger.common.event;

/** {@code eventType} values in the event envelope; UPPER_SNAKE form of the topic name (docs/conventions.md §3). */
public final class EventTypes {

    public static final String TRADE_CREATED = "TRADE_CREATED";
    public static final String TRADE_SETTLED = "TRADE_SETTLED";
    public static final String TRADE_CANCELLED = "TRADE_CANCELLED";

    /** Kafka record header carrying the correlation ID of the request that caused the event. */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private EventTypes() {
    }
}
