package com.alphaledger.common.event;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Idempotent-consumer guard (architecture.md §4.4). Kafka delivers at least once, so a consumer records each event ID
 * in its {@code processed_event} table in the same transaction as its own changes; a redelivered event is skipped.
 */
public class ProcessedEvents {

    private final JdbcTemplate jdbc;

    public ProcessedEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Records the event; true the first time, false if it was already processed. Call inside the transaction. */
    public boolean markProcessed(UUID eventId) {
        return jdbc.update("INSERT INTO processed_event (event_id, processed_at) VALUES (?, now()) ON CONFLICT DO NOTHING",
                eventId) == 1;
    }
}
