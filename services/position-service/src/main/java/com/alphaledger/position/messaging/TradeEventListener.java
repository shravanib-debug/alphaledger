package com.alphaledger.position.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.alphaledger.common.event.EventEnvelope;
import com.alphaledger.common.event.EventReader;
import com.alphaledger.common.event.Topics;
import com.alphaledger.common.event.TradeEvent;
import com.alphaledger.common.web.CorrelationId;
import com.alphaledger.position.service.PositionService;

/**
 * Consumes trade events. Partitioning by portfolio keeps one portfolio's events in order; at-least-once delivery is
 * made safe by the processed_event check. The event's correlation ID is carried into logs and the next event.
 */
@Component
public class TradeEventListener {

    private final EventReader reader;
    private final PositionService positions;

    public TradeEventListener(EventReader reader, PositionService positions) {
        this.reader = reader;
        this.positions = positions;
    }

    @KafkaListener(topics = {Topics.TRADE_CREATED, Topics.TRADE_CANCELLED}, groupId = "position-service")
    public void onTradeEvent(ConsumerRecord<String, String> record) {
        EventEnvelope<TradeEvent> event = reader.read(record.value(), TradeEvent.class);
        if (event.correlationId() != null) {
            MDC.put(CorrelationId.MDC_KEY, event.correlationId());
        }
        try {
            positions.apply(event, Topics.TRADE_CANCELLED.equals(record.topic()));
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
