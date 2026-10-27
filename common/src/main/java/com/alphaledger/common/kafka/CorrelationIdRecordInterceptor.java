package com.alphaledger.common.kafka;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import com.alphaledger.common.web.CorrelationId;

/**
 * Puts the record's {@value CorrelationId#HEADER} header (set by the outbox relay) into the logging MDC while the
 * listener runs, including its retries and the dead-letter step, so one ID traces a request across HTTP and Kafka.
 */
public class CorrelationIdRecordInterceptor implements RecordInterceptor<Object, Object> {

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        Header header = record.headers().lastHeader(CorrelationId.HEADER);
        if (header != null) {
            MDC.put(CorrelationId.MDC_KEY,
                    CorrelationId.sanitizeOrGenerate(new String(header.value(), StandardCharsets.UTF_8)));
        }
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        MDC.remove(CorrelationId.MDC_KEY);
    }
}
