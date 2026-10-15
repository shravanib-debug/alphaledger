package com.alphaledger.trade.outbox;

import java.sql.Timestamp;
import java.time.Clock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.alphaledger.common.event.EventEnvelope;
import com.alphaledger.common.web.CorrelationId;

/**
 * Transactional outbox (architecture.md §4.4): the event row is written in the same database transaction as the
 * business change, so either both are committed or neither is. {@link OutboxRelay} publishes it to Kafka later.
 */
@Component
public class OutboxWriter {

    static final String PRODUCER = "trade-service";

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, JsonMapper jsonMapper, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** Must join the caller's transaction; never runs on its own. */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> EventEnvelope<T> append(String aggregateType, String aggregateId, String eventType, String topic,
                                       String messageKey, T payload) {
        String correlationId = CorrelationId.current() != null ? CorrelationId.current() : CorrelationId.generate();
        EventEnvelope<T> envelope = EventEnvelope.of(eventType, PRODUCER, correlationId, payload);
        jdbc.update("""
                INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, topic, message_key, payload,
                                          status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'PENDING', ?)
                """, envelope.eventId(), aggregateType, aggregateId, eventType, topic, messageKey,
                jsonMapper.writeValueAsString(envelope), Timestamp.from(clock.instant()));
        return envelope;
    }
}
