package com.alphaledger.common.autoconfigure;

import io.micrometer.core.instrument.Metrics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import tools.jackson.core.JacksonException;

import com.alphaledger.common.event.InvalidEventException;
import com.alphaledger.common.kafka.CorrelationIdRecordInterceptor;
import com.alphaledger.common.kafka.DeadLetterReplayer;
import com.alphaledger.common.kafka.DeadLetterTopics;
import com.alphaledger.common.kafka.KafkaRetryProperties;

/**
 * Consumer resilience for every service that consumes Kafka (one that sets {@code spring.kafka.consumer.group-id};
 * architecture.md §4.4). Spring Boot wires the error handler and interceptor into the default listener factory.
 * <ul>
 * <li>A failing record is retried with exponential backoff, then published to {@code <topic>.DLT} with the
 * exception in headers, and the consumer moves on: one bad record never blocks a partition.</li>
 * <li>A record that cannot be read (bad JSON, missing payload) goes to the dead-letter topic at once.</li>
 * <li>The dead-letter topic is chosen by the record's key (partition left to the producer), so it works whatever the
 * topic's partition count; ordering per portfolio is kept.</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(KafkaOperations.class)
@ConditionalOnProperty(name = "spring.kafka.consumer.group-id")
@EnableConfigurationProperties(KafkaRetryProperties.class)
public class KafkaResilienceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaResilienceAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafka, KafkaRetryProperties retry) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka, (record, ex) -> {
            log.error("Dead-lettering {}-{}@{} after failure: {}", record.topic(), record.partition(), record.offset(),
                    ex.getMessage());
            Metrics.counter("alphaledger.dead.letters", "topic", record.topic()).increment();
            return new TopicPartition(DeadLetterTopics.of(record.topic()), -1);
        });
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxRetries());
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(JacksonException.class, InvalidEventException.class);
        return handler;
    }

    @Bean
    @ConditionalOnMissingBean(RecordInterceptor.class)
    public RecordInterceptor<Object, Object> correlationIdRecordInterceptor() {
        return new CorrelationIdRecordInterceptor();
    }

    /** Creates the dead-letter topics up front (creating an existing topic is a no-op). */
    @Bean
    public KafkaAdmin.NewTopics deadLetterTopics(KafkaRetryProperties retry) {
        return new KafkaAdmin.NewTopics(DeadLetterTopics.CONSUMED.stream()
                .map(topic -> TopicBuilder.name(DeadLetterTopics.of(topic))
                        .partitions(retry.deadLetterPartitions()).replicas(retry.deadLetterReplicas()).build())
                .toArray(NewTopic[]::new));
    }

    @Bean
    @ConditionalOnMissingBean
    public DeadLetterReplayer deadLetterReplayer(ConsumerFactory<?, ?> consumerFactory,
                                                 KafkaOperations<Object, Object> kafka) {
        return new DeadLetterReplayer(consumerFactory, kafka);
    }
}
