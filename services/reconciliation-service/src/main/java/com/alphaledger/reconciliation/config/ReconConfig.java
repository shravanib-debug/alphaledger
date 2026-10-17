package com.alphaledger.reconciliation.config;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;

import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.event.EventReader;
import com.alphaledger.common.event.ProcessedEvents;
import com.alphaledger.common.event.Topics;
import com.alphaledger.common.outbox.OutboxRelay;
import com.alphaledger.common.outbox.OutboxWriter;
import com.alphaledger.reconciliation.matching.MatchingEngine;

@Configuration
@EnableScheduling
public class ReconConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public MarketClock marketClock(Clock clock, @Value("${alphaledger.market.zone:Asia/Kolkata}") ZoneId zone) {
        return new MarketClock(clock, zone);
    }

    /** Price tolerance in INR (prd.md business rule 5); quantities must match exactly. */
    @Bean
    public MatchingEngine matchingEngine(@Value("${alphaledger.recon.price-tolerance:0.01}") BigDecimal tolerance) {
        return new MatchingEngine(tolerance);
    }

    @Bean
    public EventReader eventReader(JsonMapper jsonMapper) {
        return new EventReader(jsonMapper);
    }

    @Bean
    public ProcessedEvents processedEvents(JdbcTemplate jdbc) {
        return new ProcessedEvents(jdbc);
    }

    @Bean
    public OutboxWriter outboxWriter(JdbcTemplate jdbc, JsonMapper jsonMapper, Clock clock,
                                     @Value("${spring.application.name}") String producer) {
        return new OutboxWriter(jdbc, jsonMapper, clock, producer);
    }

    @Bean
    @ConditionalOnProperty(name = "alphaledger.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
    public OutboxRelay outboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, JsonMapper jsonMapper,
                                   Clock clock, PlatformTransactionManager transactionManager) {
        return new OutboxRelay(jdbc, kafka, jsonMapper, clock, transactionManager);
    }

    /** Break topics: 3 partitions, 14-day retention (architecture.md §4.1). */
    @Bean
    public NewTopic reconBreakCreatedTopic(@Value("${alphaledger.kafka.replicas:1}") int replicas) {
        return topic(Topics.RECON_BREAK_CREATED, replicas);
    }

    @Bean
    public NewTopic reconBreakResolvedTopic(@Value("${alphaledger.kafka.replicas:1}") int replicas) {
        return topic(Topics.RECON_BREAK_RESOLVED, replicas);
    }

    private static NewTopic topic(String name, int replicas) {
        return TopicBuilder.name(name).partitions(3).replicas(replicas)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(14).toMillis())).build();
    }
}
