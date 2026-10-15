package com.alphaledger.valuation.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.alphaledger.common.calendar.BusinessDayCalendar;
import com.alphaledger.common.calendar.JdbcHolidayCalendarLoader;
import com.alphaledger.common.calendar.MarketClock;
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
}
