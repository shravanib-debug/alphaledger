package com.alphaledger.valuation.valuation;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.alphaledger.common.event.EventEnvelope;
import com.alphaledger.common.event.EventReader;
import com.alphaledger.common.event.PositionEvent;
import com.alphaledger.common.event.Topics;
import com.alphaledger.common.web.CorrelationId;

/** Keeps valuation-service's copy of holdings current from {@code position.updated}. */
@Component
public class PositionEventListener {

    private final EventReader reader;
    private final ValuationService valuations;

    public PositionEventListener(EventReader reader, ValuationService valuations) {
        this.reader = reader;
        this.valuations = valuations;
    }

    @KafkaListener(topics = Topics.POSITION_UPDATED, groupId = "valuation-service")
    public void onPositionUpdated(ConsumerRecord<String, String> record) {
        EventEnvelope<PositionEvent> event = reader.read(record.value(), PositionEvent.class);
        if (event.correlationId() != null) {
            MDC.put(CorrelationId.MDC_KEY, event.correlationId());
        }
        try {
            valuations.onPositionUpdated(event);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
