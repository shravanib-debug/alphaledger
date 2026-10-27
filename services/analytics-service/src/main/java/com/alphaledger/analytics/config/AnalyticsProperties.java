package com.alphaledger.analytics.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code alphaledger.analytics.*}: the source databases the ETL extracts from (read-only) and the nightly schedule.
 *
 * @param sources keyed by {@link Source} name in lower case: portfolio, trade, position, valuation, recon
 */
@ConfigurationProperties(prefix = "alphaledger.analytics")
public record AnalyticsProperties(Map<String, SourceDb> sources, String zone) {

    public AnalyticsProperties {
        sources = sources == null ? Map.of() : sources;
        zone = zone == null ? "Asia/Kolkata" : zone;
    }

    public enum Source { PORTFOLIO, TRADE, POSITION, VALUATION, RECON }

    public record SourceDb(String url, String username, String password) {
    }
}
