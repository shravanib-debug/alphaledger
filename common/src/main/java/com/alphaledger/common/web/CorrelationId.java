package com.alphaledger.common.web;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/** Correlation ID conventions shared by HTTP filters and Kafka producers/consumers. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    // Accept only short, log-safe values from callers; anything else is replaced.
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationId() {
    }

    /** Returns the incoming value if it is safe to log, otherwise a new random ID. */
    public static String sanitizeOrGenerate(String incoming) {
        return incoming != null && VALID.matcher(incoming).matches() ? incoming : generate();
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /** The correlation ID of the current request or message, or {@code null} outside one. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
