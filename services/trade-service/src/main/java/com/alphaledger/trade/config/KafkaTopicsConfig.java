package com.alphaledger.trade.config;

import java.time.Duration;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.alphaledger.common.event.Topics;

/** Topics produced by trade-service (architecture.md §4.1): 3 partitions keyed by portfolio, 7-day retention. */
@Configuration
public class KafkaTopicsConfig {

    private static final String SEVEN_DAYS = String.valueOf(Duration.ofDays(7).toMillis());

    @Bean
    public NewTopic tradeCreatedTopic(TradeProperties properties) {
        return topic(Topics.TRADE_CREATED, properties);
    }

    @Bean
    public NewTopic tradeSettledTopic(TradeProperties properties) {
        return topic(Topics.TRADE_SETTLED, properties);
    }

    @Bean
    public NewTopic tradeCancelledTopic(TradeProperties properties) {
        return topic(Topics.TRADE_CANCELLED, properties);
    }

    private static NewTopic topic(String name, TradeProperties properties) {
        return TopicBuilder.name(name)
                .partitions(3)
                .replicas(properties.kafkaReplicas())
                .config(TopicConfig.RETENTION_MS_CONFIG, SEVEN_DAYS)
                .build();
    }
}
