package com.alphaledger.notification.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import tools.jackson.databind.json.JsonMapper;

import com.alphaledger.common.event.EventReader;
import com.alphaledger.common.event.ProcessedEvents;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public EventReader eventReader(JsonMapper jsonMapper) {
        return new EventReader(jsonMapper);
    }

    @Bean
    public ProcessedEvents processedEvents(JdbcTemplate jdbc) {
        return new ProcessedEvents(jdbc);
    }
}
