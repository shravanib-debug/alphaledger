package com.alphaledger.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The ETL end to end. One Postgres container holds analytics_db (the service's own database) and the five source
 * databases, each created by its service's real Flyway migrations, so a source schema change that breaks the
 * extract fails here. Seeded data and the expected numbers are worked out by hand in the comments.
 * Clock fixed to Friday 16-Oct-2026 10:00 IST.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AnalyticsEtlIT {

    private static final String ADMIN = TestTokens.bearer(1, "ADMIN");
    private static final String PM = TestTokens.bearer(2, "PM");
    private static final String SOURCES = "portfolio:portfolio-service,trade:trade-service,position:position-service,"
            + "valuation:valuation-service,recon:reconciliation-service";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15")
            .withInitScript("create-source-databases.sql");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-10-16T04:30:00Z"), ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        for (String pair : SOURCES.split(",")) {
            String name = pair.split(":")[0];
            registry.add("alphaledger.analytics.sources." + name + ".url", () -> url(name));
            registry.add("alphaledger.analytics.sources." + name + ".username", POSTGRES::getUsername);
            registry.add("alphaledger.analytics.sources." + name + ".password", POSTGRES::getPassword);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate warehouse;

    @BeforeAll
    static void createAndSeedSources() {
        for (String pair : SOURCES.split(",")) {
            String[] p = pair.split(":");
            Flyway.configure().dataSource(url(p[0]), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("filesystem:../" + p[1] + "/src/main/resources/db/migration").load().migrate();
        }
        source("portfolio").batchUpdate(
                "INSERT INTO portfolio (id, name, manager_id) VALUES (1, 'Bharat Growth', 2), (2, 'Bharat Real Assets', 2)",
                """
                INSERT INTO instrument (id, isin, symbol, exchange, name, asset_class, asset_class_proxy, instrument_type)
                VALUES (1, 'INE041025011', 'EMBASSY', 'NSE', 'Embassy Office Parks REIT', 'REAL_ESTATE', 'LISTED_REIT', 'REIT'),
                       (9, 'INE134E08MQ2', 'PFC34', 'NSE', 'PFC 7.4% 2034', 'CREDIT', 'LISTED_BOND', 'BOND'),
                       (18, 'INE002A01018', 'RELIANCE', 'NSE', 'Reliance Industries', 'PRIVATE_EQUITY', 'LISTED_EQUITY', 'EQUITY')
                """);
        source("trade").batchUpdate("""
                INSERT INTO trade (id, trade_ref, portfolio_id, instrument_id, isin, side, quantity, price, trade_date,
                                   settle_date, status, created_by, created_at, updated_at)
                VALUES (101, 'TR-101', 2, 1, 'INE041025011', 'BUY', 500, 430, '2026-10-13', '2026-10-14', 'SETTLED', 2,
                        '2026-10-13 10:00+05:30', '2026-10-14 18:00+05:30'),
                       (102, 'TR-102', 1, 18, 'INE002A01018', 'BUY', 100, 1200, '2026-10-13', '2026-10-14', 'SETTLED', 2,
                        '2026-10-13 10:05+05:30', '2026-10-14 18:00+05:30'),
                       (103, 'TR-103', 2, 9, 'INE134E08MQ2', 'BUY', 1000, 98, '2026-10-15', '2026-10-16', 'VALIDATED', 2,
                        '2026-10-15 11:00+05:30', '2026-10-15 11:00+05:30'),
                       (104, 'TR-104', 1, NULL, 'INE000000000', 'SELL', 5, 10, '2026-10-15', '2026-10-16', 'REJECTED', 2,
                        '2026-10-15 11:30+05:30', '2026-10-15 11:30+05:30'),
                       (105, 'TR-105', 1, 18, 'INE002A01018', 'BUY', 10, 1180, '2026-10-16', '2026-10-19', 'VALIDATED', 2,
                        '2026-10-16 09:30+05:30', '2026-10-16 09:30+05:30')
                """);
        source("position").batchUpdate("""
                INSERT INTO position_snapshot (portfolio_id, instrument_id, isin, snapshot_date, quantity, avg_cost,
                                               realized_pnl)
                VALUES (2, 1, 'INE041025011', '2026-10-14', 500, 430, 0),
                       (1, 18, 'INE002A01018', '2026-10-14', 100, 1200, 0),
                       (2, 1, 'INE041025011', '2026-10-15', 500, 430, 0),
                       (2, 9, 'INE134E08MQ2', '2026-10-15', 1000, 98, 0),
                       (1, 18, 'INE002A01018', '2026-10-15', 100, 1200, 500)
                """);
        source("valuation").batchUpdate("""
                INSERT INTO instrument_ref (instrument_id, isin, name, instrument_type, asset_class, asset_class_proxy,
                                            is_synthetic)
                VALUES (1, 'INE041025011', 'Embassy', 'REIT', 'REAL_ESTATE', 'LISTED_REIT', FALSE),
                       (9, 'INE134E08MQ2', 'PFC', 'BOND', 'CREDIT', 'LISTED_BOND', FALSE),
                       (18, 'INE002A01018', 'Reliance', 'EQUITY', 'PRIVATE_EQUITY', 'LISTED_EQUITY', FALSE)
                """, """
                INSERT INTO price (instrument_id, isin, price, data_date, as_of_timestamp, source)
                VALUES (1, 'INE041025011', 434.91, '2026-10-14', '2026-10-14 18:00+05:30', 'NSE_BHAVCOPY'),
                       (1, 'INE041025011', 440.00, '2026-10-15', '2026-10-15 18:00+05:30', 'NSE_BHAVCOPY'),
                       (9, 'INE134E08MQ2', 97.60, '2026-10-14', '2026-10-14 18:00+05:30', 'NSE_BHAVCOPY'),
                       (18, 'INE002A01018', 1174.60, '2026-10-14', '2026-10-14 18:00+05:30', 'NSE_BHAVCOPY'),
                       (18, 'INE002A01018', 1000.00, '2026-10-14', '2026-10-14 19:00+05:30', 'SYNTHETIC')
                """, """
                INSERT INTO valuation_snapshot (portfolio_id, as_of_date, market_value, total_cost, unrealized_pnl,
                    realized_pnl, cash_balance, total_liabilities, nav, valuation_basis, priced_positions,
                    stale_positions, unpriced_positions, created_at)
                VALUES (1, '2026-10-14', 117460, 120000, -2540, 0, 10000, 0, 127460, 'FULL_NAV', 1, 0, 0, now()),
                       (1, '2026-10-15', 117460, 120000, -2540, 500, 20000, 150000, -12540, 'FULL_NAV', 1, 1, 0, now()),
                       (2, '2026-10-15', 317600, 313000, 4600, 0, 0, 0, 317600, 'FULL_NAV', 2, 1, 0, now())
                """);
        source("recon").batchUpdate("""
                INSERT INTO recon_run (id, run_date, source, file_checksum, status, started_at)
                VALUES (1, '2026-10-14', 'CUSTODIAN_A', 'abc', 'COMPLETED', '2026-10-13 09:00+05:30')
                """, """
                INSERT INTO recon_break (id, run_id, break_type, trade_ref, isin, trade_date, portfolio_id, status,
                                         resolution_type, created_at, updated_at, resolved_at)
                VALUES (1, 1, 'MISSING_INTERNAL', 'CUST-1', 'INE041025011', '2026-10-12', NULL, 'OPEN', NULL,
                        '2026-10-13 10:00+05:30', '2026-10-13 10:00+05:30', NULL),
                       (2, 1, 'QTY_MISMATCH', 'TR-101', 'INE041025011', '2026-10-13', 2, 'RESOLVED', 'KNOWN_DIFFERENCE',
                        '2026-10-14 10:00+05:30', '2026-10-15 12:00+05:30', '2026-10-15 12:00+05:30'),
                       (3, 1, 'PRICE_MISMATCH', 'TR-102', 'INE002A01018', '2026-10-13', 1, 'OPEN', NULL,
                        '2026-10-15 09:00+05:30', '2026-10-15 09:00+05:30', NULL),
                       (4, 1, 'QTY_MISMATCH', 'TR-102', 'INE002A01018', '2026-10-13', 1, 'IN_REVIEW', NULL,
                        '2026-10-15 09:30+05:30', '2026-10-15 09:30+05:30', NULL)
                """);
    }

    @Test
    void theEtlLoadsTheStarSchemaChecksQualityAndServesTheMarts() throws Exception {
        // ---- Full refresh up to Thursday 15-Oct ----
        mockMvc.perform(post("/api/v1/analytics/etl-runs").param("date", "2026-10-15").param("fullRefresh", "true")
                .header("Authorization", PM)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/analytics/etl-runs").param("date", "2026-10-15").param("fullRefresh", "true")
                        .header("Authorization", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // Trades on or before 15-Oct: 101-104 (105 is dated 16-Oct). The rejected trade's unknown ISIN maps to the
        // Unknown instrument (-1), so it is kept, and reported as a warning.
        assertThat(count("core.fact_trade")).isEqualTo(4);
        assertThat(warehouse.queryForObject("SELECT notional FROM core.fact_trade WHERE trade_id = 101",
                BigDecimal.class)).isEqualByComparingTo("215000.00");
        assertThat(warehouse.queryForObject("SELECT instrument_key FROM core.fact_trade WHERE trade_id = 104",
                Long.class)).isEqualTo(-1L);

        // Positions on 15-Oct: Embassy 500 x 440.00 = 220 000.00 (unrealized 220 000 - 215 000 = 5 000);
        // PFC 1000 x 97.60 (14-Oct price, carried forward) = 97 600.00; Reliance 100 x 1174.60 = 117 460.00 (the
        // NSE close wins over the synthetic mark of the same day).
        assertThat(marketValue(20261015, 1)).isEqualByComparingTo("220000.00");
        assertThat(marketValue(20261015, 9)).isEqualByComparingTo("97600.00");
        assertThat(marketValue(20261015, 18)).isEqualByComparingTo("117460.00");
        assertThat(warehouse.queryForObject("""
                SELECT f.unrealized_pnl FROM core.fact_position_daily f
                JOIN core.dim_instrument i ON i.instrument_key = f.instrument_key
                WHERE f.date_key = 20261015 AND i.instrument_id = 1
                """, BigDecimal.class)).isEqualByComparingTo("5000.00");

        // AUM 15-Oct: 220 000 + 117 460 + 97 600 = 435 060 -> 50.57%, 27.00%, 22.43%.
        mockMvc.perform(get("/api/v1/analytics/aum-by-asset-class").header("Authorization", PM))
                .andExpect(jsonPath("$.date").value("2026-10-15"))
                .andExpect(jsonPath("$.rows[0].asset_class").value("REAL_ESTATE"))
                .andExpect(jsonPath("$.rows[0].pct_of_total").value(50.57))
                .andExpect(jsonPath("$.rows[1].asset_class").value("PRIVATE_EQUITY"))
                .andExpect(jsonPath("$.rows[1].pct_of_total").value(27.00))
                .andExpect(jsonPath("$.rows[2].market_value").value(97600.00))
                .andExpect(jsonPath("$.rows[2].pct_of_total").value(22.43));
        // 14-Oct: Embassy 500 x 434.91 = 217 455.00 plus Reliance 117 460.00.
        mockMvc.perform(get("/api/v1/analytics/aum-by-asset-class").param("date", "2026-10-14")
                        .header("Authorization", PM))
                .andExpect(jsonPath("$.rows[0].market_value").value(217455.00));

        // Daily P&L, portfolio 1: total P&L -2 540 on 14-Oct, 500 - 2 540 = -2 040 on 15-Oct -> +500 that day.
        // Its 15-Oct NAV is 117 460 + 20 000 - 150 000 = -12 540: allowed, reported as INFO.
        mockMvc.perform(get("/api/v1/analytics/daily-pnl").param("portfolioId", "1").param("from", "2026-10-01")
                        .header("Authorization", PM))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].daily_pnl").doesNotExist())
                .andExpect(jsonPath("$[1].total_pnl").value(-2040.00))
                .andExpect(jsonPath("$[1].daily_pnl").value(500.00))
                .andExpect(jsonPath("$[1].nav").value(-12540.00));

        // Breaks, as of 15-Oct. Trend: 13-Oct one missing-internal raised; 14-Oct a qty mismatch raised;
        // 15-Oct qty: one raised (4), one resolved (2), one open at the end of the day; price: one raised and open.
        mockMvc.perform(get("/api/v1/analytics/break-trend").param("from", "2026-10-13").param("to", "2026-10-15")
                        .header("Authorization", PM))
                .andExpect(jsonPath("$.length()").value(9))   // 3 days x 3 break types
                .andExpect(jsonPath("$[?(@.date == '2026-10-15' && @.break_type == 'QTY_MISMATCH')].raised").value(1))
                .andExpect(jsonPath("$[?(@.date == '2026-10-15' && @.break_type == 'QTY_MISMATCH')].resolved").value(1))
                .andExpect(jsonPath("$[?(@.date == '2026-10-15' && @.break_type == 'QTY_MISMATCH')].open_at_end_of_day")
                        .value(1))
                .andExpect(jsonPath("$[?(@.date == '2026-10-15' && @.break_type == 'MISSING_INTERNAL')]"
                        + ".open_at_end_of_day").value(1));
        // Ageing (open breaks only): missing internal raised 13-Oct -> 2 days; the two raised 15-Oct -> 0 days.
        mockMvc.perform(get("/api/v1/analytics/break-ageing").header("Authorization", PM))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.break_type == 'MISSING_INTERNAL')].age_bucket").value("2-3 days"))
                .andExpect(jsonPath("$[?(@.break_type == 'MISSING_INTERNAL')].oldest_days").value(2))
                .andExpect(jsonPath("$[?(@.break_type == 'PRICE_MISMATCH')].age_bucket").value("0-1 days"));

        // Data quality: every critical check passed; the Unknown-member trade is a warning; one negative NAV noted.
        String dq = mockMvc.perform(get("/api/v1/analytics/dq-results").header("Authorization", PM))
                .andReturn().getResponse().getContentAsString();
        assertThat(dq).contains("\"check_name\":\"rowcount_trade\"");
        assertThat(checks("CRITICAL", "FAILED")).isZero();
        assertThat(check("ref_unknown_members")).containsEntry("status", "FAILED").containsEntry("actual", "1");
        assertThat(check("info_negative_nav")).containsEntry("status", "PASSED").containsEntry("actual", "1");
        assertThat(check("rowcount_position_daily")).containsEntry("expected", "5").containsEntry("actual", "5");

        // ---- Incremental run for Friday 16-Oct, after changes in the sources ----
        source("trade").update("UPDATE trade SET status = 'SETTLED', updated_at = '2026-10-16 08:00+05:30' WHERE id = 103");
        source("recon").update("UPDATE recon_break SET status = 'RESOLVED', resolution_type = 'ACCEPT_EXTERNAL', "
                + "resolved_at = '2026-10-16 09:00+05:30', updated_at = '2026-10-16 09:00+05:30' WHERE id = 1");
        mockMvc.perform(post("/api/v1/analytics/etl-runs").param("date", "2026-10-16").header("Authorization", ADMIN))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.rows_extracted").value(Matchers.containsString("\"stg_trade\": 2")));   // 103, 105
        assertThat(count("core.fact_trade")).isEqualTo(5);
        assertThat(warehouse.queryForObject("SELECT status FROM core.fact_trade WHERE trade_id = 103", String.class))
                .isEqualTo("SETTLED");
        // Open breaks aged by a day; the resolved missing-internal break left the ageing view.
        mockMvc.perform(get("/api/v1/analytics/break-ageing").header("Authorization", PM))
                .andExpect(jsonPath("$[?(@.break_type == 'MISSING_INTERNAL')]").isEmpty())
                .andExpect(jsonPath("$[?(@.break_type == 'PRICE_MISMATCH')].oldest_days").value(1));
        // No snapshots for 16-Oct yet: a freshness warning, not a failure.
        assertThat(check("fresh_valuation_snapshot")).containsEntry("status", "FAILED")
                .containsEntry("severity", "WARNING");

        // ---- Re-running the same date changes nothing (idempotent) ----
        long positions = count("core.fact_position_daily");
        mockMvc.perform(post("/api/v1/analytics/etl-runs").param("date", "2026-10-16").header("Authorization", ADMIN))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(count("core.fact_trade")).isEqualTo(5);
        assertThat(count("core.fact_position_daily")).isEqualTo(positions);
        assertThat(count("core.fact_recon_break")).isEqualTo(4);

        // ---- A critical data-quality failure fails the run, and the results are kept ----
        source("position").update("""
                INSERT INTO position_snapshot (portfolio_id, instrument_id, isin, snapshot_date, quantity, avg_cost,
                                               realized_pnl)
                VALUES (1, 9, 'INE134E08MQ2', '2026-10-14', -5, 98, 0)
                """);
        mockMvc.perform(post("/api/v1/analytics/etl-runs").param("date", "2026-10-14").header("Authorization", ADMIN))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("sanity_position_market_value")));
        assertThat(check("sanity_position_market_value")).containsEntry("status", "FAILED")
                .containsEntry("actual", "1");
        mockMvc.perform(get("/api/v1/analytics/etl-runs").header("Authorization", PM))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[1].status").value("COMPLETED"));
        mockMvc.perform(post("/api/v1/analytics/etl-runs").param("date", "2026-10-17").header("Authorization", ADMIN))
                .andExpect(status().isBadRequest());   // future date
    }

    private BigDecimal marketValue(int dateKey, long instrumentId) {
        return warehouse.queryForObject("""
                SELECT sum(f.market_value) FROM core.fact_position_daily f
                JOIN core.dim_instrument i ON i.instrument_key = f.instrument_key
                WHERE f.date_key = ? AND i.instrument_id = ?
                """, BigDecimal.class, dateKey, instrumentId);
    }

    private long count(String table) {
        return warehouse.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    /** The named check's result in the latest run. */
    private Map<String, Object> check(String name) {
        return warehouse.queryForMap("SELECT status, severity, expected, actual FROM core.dq_check_result "
                + "WHERE check_name = ? AND etl_run_id = (SELECT max(id) FROM core.etl_run)", name);
    }

    private long checks(String severity, String status) {
        return warehouse.queryForObject("SELECT count(*) FROM core.dq_check_result WHERE severity = ? AND status = ? "
                + "AND etl_run_id = (SELECT max(id) FROM core.etl_run)", Long.class, severity, status);
    }

    private static JdbcTemplate source(String name) {
        return new JdbcTemplate(new DriverManagerDataSource(url(name), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static String url(String name) {
        return POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name + "_db");
    }
}
