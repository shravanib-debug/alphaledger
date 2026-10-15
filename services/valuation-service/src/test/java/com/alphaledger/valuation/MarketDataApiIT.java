package com.alphaledger.valuation;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

/**
 * Market data end to end on Postgres, with the clock fixed to Thursday 15-Oct-2026 11:30 IST: startup loads the
 * instrument master, the NSE holidays, the real seed closes (14-Oct) and the synthetic quarterly marks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class MarketDataApiIT {

    private static final String ADMIN = TestTokens.bearer(1, "ADMIN");
    private static final String PM = TestTokens.bearer(2, "PM");
    private static final String AUDITOR = TestTokens.bearer(4, "AUDITOR");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-10-15T06:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void seedPricesAreRealAndCarryTheirSourceBadge() throws Exception {
        mockMvc.perform(get("/api/v1/marketdata/prices/latest").param("isin", "INE041025011")
                        .header("Authorization", AUDITOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].price").value(434.91))
                .andExpect(jsonPath("$[0].source").value("NSE_BHAVCOPY"))
                .andExpect(jsonPath("$[0].dataDate").value("2026-10-14"))
                // Today is 15-Oct, so the 14-Oct close is one business day old: stale, but no warning yet.
                .andExpect(jsonPath("$[0].expectedDate").value("2026-10-15"))
                .andExpect(jsonPath("$[0].stale").value(true))
                .andExpect(jsonPath("$[0].staleBusinessDays").value(1))
                .andExpect(jsonPath("$[0].stalenessWarning").value(false));
        mockMvc.perform(get("/api/v1/marketdata/prices/latest").param("isin", "INE041025011").param("asOf", "2026-10-14")
                        .header("Authorization", AUDITOR))
                .andExpect(jsonPath("$[0].stale").value(false));
        mockMvc.perform(get("/api/v1/marketdata/prices/latest").param("isin", "INF179K01XD8")
                        .header("Authorization", AUDITOR))
                .andExpect(jsonPath("$[0].source").value("AMFI_NAV"))
                .andExpect(jsonPath("$[0].price").value(35.0155));
    }

    @Test
    void syntheticInstrumentsHaveLevel3QuarterlyMarks() throws Exception {
        mockMvc.perform(get("/api/v1/marketdata/prices/latest").param("isin", "ZZSYN0000001")
                        .header("Authorization", AUDITOR))
                .andExpect(jsonPath("$[0].price").value(1360.0))
                .andExpect(jsonPath("$[0].source").value("SYNTHETIC"))
                .andExpect(jsonPath("$[0].fairValueLevel").value("L3"))
                .andExpect(jsonPath("$[0].dataDate").value("2026-09-30"))
                .andExpect(jsonPath("$[0].expectedDate").value("2026-09-30"))
                .andExpect(jsonPath("$[0].stale").value(false));
    }

    @Test
    void everyDemoInstrumentHasAPrice() throws Exception {
        mockMvc.perform(get("/api/v1/marketdata/prices/latest").header("Authorization", AUDITOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(29))
                .andExpect(jsonPath("$[?(@.price == null)]").isEmpty());
    }

    @Test
    void uploadIsAdminOnlyIdempotentAndReportsRejectedRows() throws Exception {
        String csv = """
                isin,data_date,price
                INE002A01018,2026-10-15,1180.00
                INE154A01025,2026-10-15,400.00
                INE040A01034,2026-10-02,705.00
                ZZSYN0000002,2026-10-14,999.00
                """;
        MockMultipartFile file = new MockMultipartFile("file", "manual.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/marketdata/prices/upload").file(file).header("Authorization", PM))
                .andExpect(status().isForbidden());
        String body = mockMvc.perform(multipart("/api/v1/marketdata/prices/upload").file(file)
                        .header("Authorization", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.rowsLoaded").value(1))
                .andExpect(jsonPath("$.rowsRejected").value(3))
                .andReturn().getResponse().getContentAsString();
        long ingestionId = ((Number) JsonPath.read(body, "$.ingestionId")).longValue();

        mockMvc.perform(get("/api/v1/marketdata/ingestions/" + ingestionId + "/errors").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].reason").value(hasItem("Unknown ISIN INE154A01025")))
                .andExpect(jsonPath("$[*].reason").value(hasItem("data_date 2026-10-02 is not a business day")))
                .andExpect(jsonPath("$[*].reason").value(hasItem("Synthetic instruments are priced only by SYNTHETIC marks")));
        mockMvc.perform(multipart("/api/v1/marketdata/prices/upload").file(file).header("Authorization", ADMIN))
                .andExpect(jsonPath("$.status").value("SKIPPED_DUPLICATE"))
                .andExpect(jsonPath("$.rowsLoaded").value(0));

        mockMvc.perform(get("/api/v1/marketdata/prices/latest").param("isin", "INE002A01018")
                        .header("Authorization", AUDITOR))
                .andExpect(jsonPath("$[0].price").value(1180.0))
                .andExpect(jsonPath("$[0].source").value("CSV_UPLOAD"))
                .andExpect(jsonPath("$[0].stale").value(false));
    }

    @Test
    void ingestionLogShowsStartupLoadsAndReRunsAreSkipped() throws Exception {
        mockMvc.perform(get("/api/v1/marketdata/ingestions").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$[?(@.file_name == 'seed_closes_2026-10-14.csv' && @.status == 'SUCCESS')].rows_loaded")
                        .value(hasItem(26)))
                .andExpect(jsonPath("$[?(@.file_name == 'synthetic_marks.csv' && @.status == 'SUCCESS')].rows_loaded")
                        .value(hasItem(12)));
        mockMvc.perform(post("/api/v1/marketdata/ingestions").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"SYNTHETIC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SKIPPED_DUPLICATE"));
        mockMvc.perform(post("/api/v1/marketdata/ingestions").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"NSE_BHAVCOPY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("source: NSE_BHAVCOPY is not available yet"));
    }

    @Test
    void holidayCalendarIsLoaded() throws Exception {
        mockMvc.perform(get("/api/v1/marketdata/holidays").param("year", "2026").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.length()").value(20))
                .andExpect(jsonPath("$[*].description").value(hasItem("Dussehra")));
    }
}
