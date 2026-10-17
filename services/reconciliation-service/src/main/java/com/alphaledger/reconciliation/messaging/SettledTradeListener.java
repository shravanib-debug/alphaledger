package com.alphaledger.reconciliation.messaging;

import java.time.Clock;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.alphaledger.common.event.EventEnvelope;
import com.alphaledger.common.event.EventReader;
import com.alphaledger.common.event.ProcessedEvents;
import com.alphaledger.common.event.Topics;
import com.alphaledger.common.event.TradeEvent;
import com.alphaledger.reconciliation.repository.ReconRepository;

/** Keeps the internal side of reconciliation: every trade settled by trade-service (Flow B, step 3). */
@Component
public class SettledTradeListener {

    private final EventReader reader;
    private final ProcessedEvents processedEvents;
    private final ReconRepository repository;
    private final Clock clock;

    public SettledTradeListener(EventReader reader, ProcessedEvents processedEvents, ReconRepository repository,
                                Clock clock) {
        this.reader = reader;
        this.processedEvents = processedEvents;
        this.repository = repository;
        this.clock = clock;
    }

    @KafkaListener(topics = Topics.TRADE_SETTLED, groupId = "reconciliation-service")
    @Transactional
    public void onTradeSettled(ConsumerRecord<String, String> record) {
        EventEnvelope<TradeEvent> event = reader.read(record.value(), TradeEvent.class);
        if (processedEvents.markProcessed(event.eventId())) {
            repository.upsertSettledTrade(event.payload(), clock.instant());
        }
    }
}
