package com.alphaledger.valuation.config;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
import com.alphaledger.valuation.marketdata.MarketDataProperties;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(MarketDataProperties.class)
public class ValuationConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public MarketClock marketClock(Clock clock, @Value("${alphaledger.market.zone:Asia/Kolkata}") ZoneId zone) {
        return new MarketClock(clock, zone);
    }

    /** Weekends plus NSE trading holidays, loaded from the committed holiday files into holiday_calendar. */
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

    /** Topics produced here (architecture.md §4.1). */
    @Bean
    public NewTopic priceUpdatedTopic(@Value("${alphaledger.kafka.replicas:1}") int replicas) {
        return topic(Topics.PRICE_UPDATED, 3, replicas, Duration.ofDays(3));
    }

    @Bean
    public NewTopic valuationCalculatedTopic(@Value("${alphaledger.kafka.replicas:1}") int replicas) {
        return topic(Topics.VALUATION_CALCULATED, 3, replicas, Duration.ofDays(7));
    }

    @Bean
    public NewTopic limitBreachedTopic(@Value("${alphaledger.kafka.replicas:1}") int replicas) {
        return topic(Topics.LIMIT_BREACHED, 1, replicas, Duration.ofDays(7));
    }

    private static NewTopic topic(String name, int partitions, int replicas, Duration retention) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(retention.toMillis())).build();
    }
}
