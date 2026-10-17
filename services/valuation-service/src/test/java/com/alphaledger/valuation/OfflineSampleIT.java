package com.alphaledger.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Phase 4A DoD with the network off: on startup the real bhavcopy parser runs over the 7 committed NSE files
 * (8-16 Oct 2026) and {@code amfiNavIngestJob} over the committed NAVAll.txt (16-Oct), so every demo instrument has a
 * real price; re-ingesting loads nothing. Clock: Saturday 17-Oct-2026 11:30 IST.
 */
@SpringBootTest(properties = "alphaledger.marketdata.sample-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class OfflineSampleIT {

    private static final String ADMIN = TestTokens.bearer(1, "ADMIN");
    private static final String PM = TestTokens.bearer(2, "PM");
    private static final String AUDITOR = TestTokens.bearer(4, "AUDITOR");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-10-17T06:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyDemoInstrumentHasARealPriceFromTheSample() throws Exception {
        mockMvc.perform(get("/api/v1/marketdata/prices/latest").param("isin", "INE002A01018")
                        .header("Authorization", AUDITOR))
                .andExpect(jsonPath("$[0].price").value(1211.20))
                .andExpect(jsonPath("$[0].source").value("NSE_BHAVCOPY"))
                .andExpect(jsonPath("$[0].dataDate").value("2026-10-16"))
                .andExpect(jsonPath("$[0].stale").value(false));
        mockMvc.perform(get("/api/v1/marketdata/prices/latest").param("isin", "INF179K01XD8")
                        .header("Authorization", AUDITOR))
                .andExpect(jsonPath("$[0].price").value(35.0182))
                .andExpect(jsonPath("$[0].source").value("AMFI_NAV"))
                .andExpect(jsonPath("$[0].dataDate").value("2026-10-16"));
        // The PFC tax-free bond last traded on 14-Oct: its last real price stays, flagged stale, never synthetic.
        mockMvc.perform(get("/api/v1/marketdata/prices/latest").param("isin", "INE134E07570")
                        .header("Authorization", AUDITOR))
                .andExpect(jsonPath("$[0].source").value("NSE_BHAVCOPY"))
                .andExpect(jsonPath("$[0].dataDate").value("2026-10-14"))
                .andExpect(jsonPath("$[0].stale").value(true))
                .andExpect(jsonPath("$[0].staleBusinessDays").value(2));
        mockMvc.perform(get("/api/v1/marketdata/prices").param("isin", "INE002A01018")
                        .param("from", "2026-10-08").param("to", "2026-10-16").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.length()").value(7));   // one close per trading day in the window

        Integer amfiRuns = jdbc.queryForObject(
                "SELECT count(*) FROM batch_job_instance WHERE job_name = 'amfiNavIngestJob'", Integer.class);
        assertThat(amfiRuns).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theIngestionLogShowsEveryFileAndReingestingLoadsNothing() throws Exception {
        mockMvc.perform(get("/api/v1/marketdata/ingestions").param("source", "NSE_BHAVCOPY")
                        .header("Authorization", AUDITOR))
                .andExpect(jsonPath("$[?(@.status == 'SUCCESS')].file_name").isNotEmpty())
                .andExpect(jsonPath("$[?(@.status == 'SUCCESS' && @.file_name == 'BhavCopy_NSE_CM_0_0_0_20261016_F_0000.csv')].rows_loaded")
                        .value(18));
        mockMvc.perform(get("/api/v1/marketdata/ingestions").param("source", "AMFI_NAV")
                        .header("Authorization", AUDITOR))
                .andExpect(jsonPath("$[?(@.file_name == 'NAVAll_2026-10-16.txt' && @.status == 'SUCCESS')].rows_loaded")
                        .value(7));

        mockMvc.perform(post("/api/v1/marketdata/ingestions").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"NSE_BHAVCOPY\",\"dataDate\":\"2026-10-16\"}"))
                .andExpect(jsonPath("$[0].status").value("SKIPPED_DUPLICATE"))
                .andExpect(jsonPath("$[0].rowsLoaded").value(0));

        // Backfill 12-16 Oct: five business days, all already loaded.
        mockMvc.perform(post("/api/v1/marketdata/ingestions/backfill").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"NSE_BHAVCOPY\",\"from\":\"2026-10-10\",\"to\":\"2026-10-16\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[*].status", everyItem(is("SKIPPED_DUPLICATE"))));
    }

    @Test
    void aDayWithNoFileIsLoggedAsFailedAndBackfillIsAdminOnly() throws Exception {
        // 1-Oct-2026 is a business day, but there is no sample file for it (offline: no download).
        mockMvc.perform(post("/api/v1/marketdata/ingestions").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"NSE_BHAVCOPY\",\"dataDate\":\"2026-10-01\"}"))
                .andExpect(jsonPath("$[0].status").value("FAILED"));
        mockMvc.perform(get("/api/v1/marketdata/ingestions").param("source", "NSE_BHAVCOPY")
                        .header("Authorization", AUDITOR))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].error_message").value(
                        "No NSE bhavcopy for 2026-10-01 (holiday, weekend, not yet published, or not in the offline sample)"));

        mockMvc.perform(post("/api/v1/marketdata/ingestions/backfill").header("Authorization", PM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-10-12\",\"to\":\"2026-10-16\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/marketdata/ingestions/backfill").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-08-01\",\"to\":\"2026-10-16\"}"))
                .andExpect(status().isBadRequest());
    }
}
