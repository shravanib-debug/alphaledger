package com.alphaledger.analytics.config;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.alphaledger.analytics.config.AnalyticsProperties.Source;
import com.alphaledger.analytics.config.AnalyticsProperties.SourceDb;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Read-only connections to the services' databases, used only by the ETL extract (ADR 23). Held here rather than as
 * {@code DataSource} beans so Spring Boot's own analytics_db DataSource, Flyway and Spring Batch are unaffected.
 * Pools are small and read-only; a production setup would point these at read replicas.
 */
public class SourceDatabases implements DisposableBean {

    private final Map<Source, HikariDataSource> pools = new EnumMap<>(Source.class);
    private final Map<Source, JdbcTemplate> templates = new EnumMap<>(Source.class);

    public SourceDatabases(AnalyticsProperties properties) {
        for (Source source : Source.values()) {
            SourceDb db = properties.sources().get(source.name().toLowerCase());
            if (db == null || db.url() == null) {
                throw new IllegalStateException("Missing alphaledger.analytics.sources." + source.name().toLowerCase()
                        + ".url");
            }
            HikariConfig config = new HikariConfig();
            config.setPoolName("source-" + source.name().toLowerCase());
            config.setJdbcUrl(db.url());
            config.setUsername(db.username());
            config.setPassword(db.password());
            config.setReadOnly(true);
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(0);
            config.setInitializationFailTimeout(-1);   // start even if a source is down; the extract fails instead
            HikariDataSource pool = new HikariDataSource(config);
            pools.put(source, pool);
            JdbcTemplate jdbc = new JdbcTemplate(pool);
            jdbc.setFetchSize(1000);
            templates.put(source, jdbc);
        }
    }

    public JdbcTemplate jdbc(Source source) {
        return templates.get(source);
    }

    @Override
    public void destroy() {
        pools.values().forEach(HikariDataSource::close);
    }
}
