package com.alphaledger.common.kafka;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Consumer retry policy ({@code alphaledger.kafka.retry.*}): a failed record is retried {@code maxRetries} times,
 * waiting {@code initialInterval}, then multiplying by {@code multiplier} up to {@code maxInterval}; after that it goes
 * to its dead-letter topic and the consumer moves on.
 */
@ConfigurationProperties(prefix = "alphaledger.kafka.retry")
public record KafkaRetryProperties(Integer maxRetries, Duration initialInterval, Double multiplier,
                                   Duration maxInterval, Integer deadLetterPartitions, Integer deadLetterReplicas) {

    public KafkaRetryProperties {
        maxRetries = maxRetries == null ? 3 : maxRetries;
        initialInterval = initialInterval == null ? Duration.ofMillis(500) : initialInterval;
        multiplier = multiplier == null ? 2.0 : multiplier;
        maxInterval = maxInterval == null ? Duration.ofSeconds(5) : maxInterval;
        deadLetterPartitions = deadLetterPartitions == null ? 3 : deadLetterPartitions;
        deadLetterReplicas = deadLetterReplicas == null ? 1 : deadLetterReplicas;
    }
}
