package com.alphaledger.common.outbox;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.alphaledger.common.event.EventTypes;

/**
 * Publishes pending outbox rows to Kafka in creation order (at-least-once). Rows are claimed with
 * {@code FOR UPDATE SKIP LOCKED}, so several instances can run safely. On the first failure the batch stops,
 * keeping per-portfolio ordering; the row is retried on the next poll. Each producing service registers one as a
 * bean (the service must enable scheduling).
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, JsonMapper jsonMapper, Clock clock,
                       PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${alphaledger.outbox.relay.poll-interval-ms:500}")
    public void poll() {
        try {
            publishPending();
        } catch (RuntimeException ex) {
            log.warn("Outbox relay poll failed: {}", ex.getMessage());
        }
    }

    /** Publishes up to one batch in one transaction (rows stay locked until it ends); returns how many were sent. */
    public int publishPending() {
        Integer sent = transaction.execute(status -> publishBatch());
        return sent == null ? 0 : sent;
    }

    private int publishBatch() {
        List<PendingEvent> pending = jdbc.query("""
                SELECT id, topic, message_key, payload::text AS payload FROM outbox_event
                WHERE status = 'PENDING' ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, i) -> new PendingEvent(rs.getObject("id", UUID.class), rs.getString("topic"),
                rs.getString("message_key"), rs.getString("payload")), BATCH_SIZE);
        int sent = 0;
        for (PendingEvent event : pending) {
            try {
                kafka.send(record(event)).get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                jdbc.update("UPDATE outbox_event SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                        error.substring(0, Math.min(error.length(), 500)), event.id());
                log.warn("Outbox event {} not published yet ({}); will retry", event.id(), error);
                break;
            }
            jdbc.update("UPDATE outbox_event SET status = 'SENT', sent_at = ?, attempts = attempts + 1 WHERE id = ?",
                    Timestamp.from(clock.instant()), event.id());
            sent++;
        }
        return sent;
    }

    private ProducerRecord<String, String> record(PendingEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.messageKey(), event.payload());
        JsonNode correlationId = jsonMapper.readTree(event.payload()).get("correlationId");
        if (correlationId != null && !correlationId.isNull()) {
            record.headers().add(EventTypes.CORRELATION_ID_HEADER,
                    correlationId.asString().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    record PendingEvent(UUID id, String topic, String messageKey, String payload) {
    }
}
