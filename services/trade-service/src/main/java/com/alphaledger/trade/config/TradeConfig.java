package com.alphaledger.trade.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.alphaledger.common.calendar.BusinessDayCalendar;
import com.alphaledger.common.calendar.JdbcHolidayCalendarLoader;
import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.web.CorrelationId;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(TradeProperties.class)
public class TradeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** "Today" in the market's time zone (Asia/Kolkata), not the server's UTC date. */
    @Bean
    public MarketClock marketClock(Clock clock, @Value("${alphaledger.market.zone:Asia/Kolkata}") ZoneId zone) {
        return new MarketClock(clock, zone);
    }

    /** Weekends plus NSE trading holidays, loaded from the committed holiday files into holiday_calendar. */
    @Bean
    public BusinessDayCalendar businessDayCalendar(JdbcTemplate jdbc) {
        return JdbcHolidayCalendarLoader.load(jdbc);
    }

    /**
     * Plain builder for everything else, notably the Eureka client's own HTTP transport. Without this primary bean
     * Eureka would pick up the load-balanced builder and try to resolve the registry's host name through itself.
     */
    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    /** Builder whose {@code http://<service-name>} URLs are resolved through Eureka. */
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient portfolioRestClient(@LoadBalanced RestClient.Builder builder, TradeProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return builder
                .baseUrl(properties.portfolioServiceUrl())
                .requestFactory(requestFactory)
                // Forward the caller's token and correlation ID, so the call is authorized and traceable.
                .requestInterceptor((request, body, execution) -> {
                    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                        HttpServletRequest incoming = attributes.getRequest();
                        String authorization = incoming.getHeader(HttpHeaders.AUTHORIZATION);
                        if (authorization != null) {
                            request.getHeaders().set(HttpHeaders.AUTHORIZATION, authorization);
                        }
                    }
                    String correlationId = CorrelationId.current();
                    if (correlationId != null) {
                        request.getHeaders().set(CorrelationId.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
