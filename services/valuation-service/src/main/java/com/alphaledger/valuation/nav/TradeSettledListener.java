package com.alphaledger.valuation.nav;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.alphaledger.common.event.EventEnvelope;
import com.alphaledger.common.event.EventReader;
import com.alphaledger.common.event.Topics;
import com.alphaledger.common.event.TradeEvent;
import com.alphaledger.common.web.CorrelationId;

/** Moves cash when a trade settles ({@code trade.settled}): BUY pays out, SELL pays in. */
@Component
public class TradeSettledListener {

    private final EventReader reader;
    private final LedgerService ledger;

    public TradeSettledListener(EventReader reader, LedgerService ledger) {
        this.reader = reader;
        this.ledger = ledger;
    }

    @KafkaListener(topics = Topics.TRADE_SETTLED, groupId = "valuation-service")
    public void onTradeSettled(ConsumerRecord<String, String> record) {
        EventEnvelope<TradeEvent> event = reader.read(record.value(), TradeEvent.class);
        if (event.correlationId() != null) {
            MDC.put(CorrelationId.MDC_KEY, event.correlationId());
        }
        try {
            ledger.onTradeSettled(event);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
