package com.alphaledger.analytics.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.alphaledger.common.calendar.MarketClock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public MarketClock marketClock(Clock clock, AnalyticsProperties properties) {
        return new MarketClock(clock, ZoneId.of(properties.zone()));
    }

    @Bean
    public SourceDatabases sourceDatabases(AnalyticsProperties properties) {
        return new SourceDatabases(properties);
    }
}
