package com.alphaledger.common.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Envelope wrapping every Kafka event (architecture.md §4.2).
 *
 * @param <T> payload type, e.g. a trade-created payload
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String correlationId,
        String producer,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(payload, "payload");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be >= 1");
        }
    }

    /** Creates a version-1 envelope with a fresh event ID and the current time. */
    public static <T> EventEnvelope<T> of(String eventType, String producer, String correlationId, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, 1, Instant.now(), correlationId, producer, payload);
    }
}
