package com.alphaledger.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.json.JsonMapper;

import com.alphaledger.common.event.EventEnvelope;
import com.alphaledger.common.event.EventTypes;
import com.alphaledger.common.event.PositionEvent;
import com.alphaledger.common.event.Topics;

/**
 * position.updated on Kafka → holdings copy → live valuation with the real seed prices → end-of-day Spring Batch
 * run → snapshot, valuation.calculated and limit.breached. Clock fixed to Thursday 15-Oct-2026 11:30 IST.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ValuationFlowIT {

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
            return Clock.fixed(Instant.parse("2026-10-15T06:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void positionsAreValuedWithRealPricesAndTheEndOfDayRunSnapshotsAndChecksLimits() throws Exception {
        // Bharat Real Assets (portfolio 2): 500 Embassy REIT @ avg 430, 1000 IndiGrid InvIT @ avg 160.
        // Seed closes (14-Oct): Embassy 434.91, IndiGrid 168.29.
        // Market value 500 x 434.91 + 1000 x 168.29 = 217 455.00 + 168 290.00 = 385 745.00.
        // Cost 215 000.00 + 160 000.00 = 375 000.00 -> unrealized 10 745.00. Real estate = 100%.
        send(position(2, 1, "INE041025011", "500", "430", "0", 11));
        send(position(2, 6, "INE219X23014", "1000", "160", "1250.50", 12));
        awaitValuation(2, body -> JsonPath.<Integer>read(body, "$.details.pricedPositions") == 2);

        mockMvc.perform(get("/api/v1/portfolios/2/valuation").header("Authorization", AUDITOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valuationBasis").value("FULL_NAV"))
                .andExpect(jsonPath("$.cashBalance").value(0.00))   // no cash or liabilities: NAV = market value
                .andExpect(jsonPath("$.totalLiabilities").value(0.00))
                .andExpect(jsonPath("$.nav").value(385745.00))
                .andExpect(jsonPath("$.asOfDate").value("2026-10-15"))
                .andExpect(jsonPath("$.marketValue").value(385745.00))
                .andExpect(jsonPath("$.details.unrealizedPnl").value(10745.00))
                .andExpect(jsonPath("$.details.realizedPnl").value(1250.50))
                .andExpect(jsonPath("$.details.stalePositions").value(2))   // 14-Oct prices, valued on 15-Oct
                .andExpect(jsonPath("$.details.lines[0].priceSource").value("NSE_BHAVCOPY"))
                .andExpect(jsonPath("$.details.lines[0].priceDate").value("2026-10-14"));
        mockMvc.perform(get("/api/v1/portfolios/2/exposure").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.byAssetClass[0].key").value("REAL_ESTATE"))
                .andExpect(jsonPath("$.byAssetClass[0].pctOfTotal").value(100.00))
                .andExpect(jsonPath("$.byInstrument[0].pctOfTotal").value(56.37));   // 217 455 / 385 745

        // Limit: real estate at most 60% of market value, for this portfolio only.
        mockMvc.perform(post("/api/v1/valuations/limit-rules").header("Authorization", PM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"portfolioId\":2,\"assetClass\":\"REAL_ESTATE\",\"maxPct\":60}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/valuations/limit-rules").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"portfolioId\":2,\"assetClass\":\"REAL_ESTATE\",\"maxPct\":60}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/valuations/eod-runs").param("date", "2026-10-15").header("Authorization", ADMIN))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mockMvc.perform(post("/api/v1/valuations/eod-runs").param("date", "2026-10-15").header("Authorization", ADMIN))
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.startsWith("NOT_STARTED")));

        mockMvc.perform(get("/api/v1/portfolios/2/valuation/history").param("from", "2026-10-01")
                        .header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].market_value").value(385745.00))
                .andExpect(jsonPath("$[0].valuation_basis").value("FULL_NAV"))
                .andExpect(jsonPath("$[0].nav").value(385745.00));
        mockMvc.perform(get("/api/v1/valuations/limit-breaches").param("portfolioId", "2")
                        .header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].asset_class").value("REAL_ESTATE"));

        ConsumerRecord<String, String> valuation = awaitRecord(Topics.VALUATION_CALCULATED,
                v -> JsonPath.<Integer>read(v, "$.payload.portfolioId") == 2);
        assertThat(valuation.key()).isEqualTo("2");
        assertThat((Object) JsonPath.read(valuation.value(), "$.payload.valuationBasis")).isEqualTo("FULL_NAV");
        ConsumerRecord<String, String> breach = awaitRecord(Topics.LIMIT_BREACHED,
                v -> JsonPath.<Integer>read(v, "$.payload.portfolioId") == 2);
        assertThat((Object) JsonPath.read(breach.value(), "$.payload.assetClass")).isEqualTo("REAL_ESTATE");
    }

    @Test
    void seedPricesArePublishedAsPriceUpdatedEvents() {
        ConsumerRecord<String, String> price = awaitRecord(Topics.PRICE_UPDATED,
                v -> "INE041025011".equals(JsonPath.read(v, "$.payload.isin")));
        assertThat(price.key()).isEqualTo("1");
        assertThat((Object) JsonPath.read(price.value(), "$.payload.source")).isEqualTo("NSE_BHAVCOPY");
    }

    @Test
    void anOlderPositionUpdateDoesNotOverwriteANewerOne() throws Exception {
        Instant newer = Instant.parse("2026-10-15T05:00:00Z");
        send(position(5, 18, "INE002A01018", "40", "1150", "0", 51, newer));
        send(position(5, 18, "INE002A01018", "10", "1150", "0", 50, newer.minusSeconds(60)));
        send(position(5, 19, "INE040A01034", "1", "700", "0", 52));   // marker: processed after both
        awaitValuation(5, body -> JsonPath.<Integer>read(body, "$.details.pricedPositions") == 2);

        mockMvc.perform(get("/api/v1/portfolios/5/valuation").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.details.lines[?(@.instrumentId == 18)].quantity").value(40.0));
    }

    private String position(long portfolioId, long instrumentId, String isin, String qty, String avg, String realized,
                            long lastTradeId) {
        return position(portfolioId, instrumentId, isin, qty, avg, realized, lastTradeId, Instant.now());
    }

    private String position(long portfolioId, long instrumentId, String isin, String qty, String avg, String realized,
                            long lastTradeId, Instant updatedAt) {
        PositionEvent payload = new PositionEvent(portfolioId, instrumentId, isin, new BigDecimal(qty),
                new BigDecimal(avg), new BigDecimal(realized), lastTradeId, updatedAt);
        return jsonMapper.writeValueAsString(new EventEnvelope<>(UUID.randomUUID(), EventTypes.POSITION_UPDATED, 1,
                Instant.now(), "it", "position-service", payload));
    }

    private void send(String value) throws Exception {
        kafka.send(Topics.POSITION_UPDATED, JsonPath.read(value, "$.payload.portfolioId").toString(), value).get();
    }

    private void awaitValuation(long portfolioId, Predicate<String> done) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            String body = mockMvc.perform(get("/api/v1/portfolios/" + portfolioId + "/valuation")
                    .header("Authorization", AUDITOR)).andReturn().getResponse().getContentAsString();
            if (done.test(body)) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("Valuation of portfolio " + portfolioId + " not ready within 30 s");
    }

    private static ConsumerRecord<String, String> awaitRecord(String topic, Predicate<String> match) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (match.test(record.value())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No matching " + topic + " event within 20 s");
    }
}
