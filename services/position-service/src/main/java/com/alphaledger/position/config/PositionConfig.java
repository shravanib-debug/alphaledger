package com.alphaledger.position.config;

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

import com.alphaledger.common.calendar.BusinessDayCalendar;
import com.alphaledger.common.calendar.JdbcHolidayCalendarLoader;
import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.event.EventReader;
import com.alphaledger.common.event.ProcessedEvents;
import com.alphaledger.common.event.Topics;
import com.alphaledger.common.outbox.OutboxRelay;
import com.alphaledger.common.outbox.OutboxWriter;

@Configuration
@EnableScheduling
public class PositionConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public MarketClock marketClock(Clock clock, @Value("${alphaledger.market.zone:Asia/Kolkata}") ZoneId zone) {
        return new MarketClock(clock, zone);
    }

    @Bean
    public BusinessDayCalendar businessDayCalendar(JdbcTemplate jdbc) {
        return JdbcHolidayCalendarLoader.load(jdbc);
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

    /** position.updated: 3 partitions keyed by portfolio, 7-day retention (architecture.md §4.1). */
    @Bean
    public NewTopic positionUpdatedTopic(@Value("${alphaledger.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(Topics.POSITION_UPDATED).partitions(3).replicas(replicas)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(7).toMillis())).build();
    }
}
