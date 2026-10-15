package com.alphaledger.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

import com.alphaledger.common.event.Topics;
import com.alphaledger.trade.client.PortfolioClient;
import com.alphaledger.trade.client.PortfolioClient.InstrumentInfo;
import com.alphaledger.trade.client.PortfolioClient.PortfolioInfo;
import com.alphaledger.trade.service.TradeService;

/**
 * Trade capture end to end on real Postgres and Kafka: REST call → trade + audit + outbox rows in one transaction
 * → relay → message on Kafka. portfolio-service is stubbed; the clock is fixed to Wednesday 21-Oct-2026.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class TradeApiIT {

    private static final String ADMIN = TestTokens.bearer(1, "ADMIN");
    private static final String PRIYA = TestTokens.bearer(2, "PM");
    private static final String AUDITOR = TestTokens.bearer(4, "AUDITOR");
    private static final String RELIANCE = "INE002A01018";
    private static final String HDFC = "INE040A01034";

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
            return Clock.fixed(Instant.parse("2026-10-21T06:00:00Z"), ZoneOffset.UTC); // Wed 11:30 IST
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TradeService tradeService;

    @MockitoBean
    private PortfolioClient portfolioClient;

    @BeforeEach
    void stubPortfolioService() {
        when(portfolioClient.findPortfolio(anyLong())).thenReturn(Optional.empty());
        when(portfolioClient.findPortfolio(1L)).thenReturn(Optional.of(new PortfolioInfo(1, "Credit", 2, "ACTIVE")));
        when(portfolioClient.findPortfolio(2L)).thenReturn(Optional.of(new PortfolioInfo(2, "Real Assets", 2, "ACTIVE")));
        when(portfolioClient.findPortfolio(3L)).thenReturn(Optional.of(new PortfolioInfo(3, "Other PM", 99, "ACTIVE")));
        when(portfolioClient.findInstrumentByIsin(anyString())).thenReturn(Optional.empty());
        when(portfolioClient.findInstrumentByIsin(RELIANCE))
                .thenReturn(Optional.of(new InstrumentInfo(18, RELIANCE, "Reliance", "EQUITY", "ACTIVE")));
        when(portfolioClient.findInstrumentByIsin(HDFC))
                .thenReturn(Optional.of(new InstrumentInfo(19, HDFC, "HDFC Bank", "EQUITY", "ACTIVE")));
    }

    @Test
    void validBuyIsStoredAuditedAndPublishedToKafka() throws Exception {
        String ref = "IT-BUY-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/trades").header("Authorization", PRIYA)
                        .header("X-Correlation-Id", "it-corr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trade(ref, 1, RELIANCE, "BUY", "100", null)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.tradeDate").value("2026-10-21"))
                .andExpect(jsonPath("$.settleDate").value("2026-10-22"))
                .andExpect(jsonPath("$.instrumentId").value(18))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andReturn();
        long id = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(get("/api/v1/trades/" + id + "/audit").header("Authorization", AUDITOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].toStatus").value("NEW"))
                .andExpect(jsonPath("$[1].fromStatus").value("NEW"))
                .andExpect(jsonPath("$[1].toStatus").value("VALIDATED"))
                .andExpect(jsonPath("$[1].changedBy").value(2));

        ConsumerRecord<String, String> record = awaitRecord(Topics.TRADE_CREATED, ref);
        assertThat(record.key()).isEqualTo("1");
        assertThat(new String(record.headers().lastHeader("X-Correlation-Id").value(), StandardCharsets.UTF_8))
                .isEqualTo("it-corr-1");
        Map<String, Object> envelope = JsonPath.read(record.value(), "$");
        assertThat(envelope).containsEntry("eventType", "TRADE_CREATED").containsEntry("eventVersion", 1)
                .containsEntry("producer", "trade-service").containsEntry("correlationId", "it-corr-1");
        assertThat((Object) JsonPath.read(record.value(), "$.payload.isin")).isEqualTo(RELIANCE);
        assertThat((Object) JsonPath.read(record.value(), "$.payload.settleDate")).isEqualTo("2026-10-22");
        assertThat((Object) JsonPath.read(record.value(), "$.payload.status")).isEqualTo("VALIDATED");
    }

    @Test
    void sellIsCheckedAgainstTheHolding() throws Exception {
        create(PRIYA, trade(null, 2, HDFC, "BUY", "50", null)).andExpect(jsonPath("$.status").value("VALIDATED"));

        create(PRIYA, trade(null, 2, HDFC, "SELL", "80", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("SELL of 80 exceeds the current holding of 50"));
        create(PRIYA, trade(null, 2, HDFC, "SELL", "30", null)).andExpect(jsonPath("$.status").value("VALIDATED"));
    }

    @Test
    void tradeRefMakesCreationIdempotent() throws Exception {
        String ref = "IT-IDEM-" + UUID.randomUUID().toString().substring(0, 8);
        String body = trade(ref, 1, RELIANCE, "BUY", "10", null);
        String firstId = JsonPath.read(create(PRIYA, body).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString(), "$.id").toString();

        mockMvc.perform(post("/api/v1/trades").header("Authorization", PRIYA)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Long.parseLong(firstId)));
        create(PRIYA, trade(ref, 1, RELIANCE, "BUY", "11", null))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidRequestsAreRefusedAndNotStored() throws Exception {
        create(PRIYA, trade(null, 1, RELIANCE, "BUY", "0", null)).andExpect(status().isBadRequest());
        create(PRIYA, trade(null, 1, RELIANCE, "BUY", "10", "2026-10-25"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("tradeDate: cannot be in the future"));
        create(PRIYA, trade(null, 1, RELIANCE, "BUY", "10", "2026-10-18"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("tradeDate: 2026-10-18 is not a business day"));
    }

    @Test
    void rolesAndOwnershipAreEnforced() throws Exception {
        create(AUDITOR, trade(null, 1, RELIANCE, "BUY", "10", null)).andExpect(status().isForbidden());
        create(PRIYA, trade(null, 3, RELIANCE, "BUY", "10", null)).andExpect(status().isForbidden());
        create(ADMIN, trade(null, 3, RELIANCE, "BUY", "10", null)).andExpect(jsonPath("$.status").value("VALIDATED"));
        mockMvc.perform(get("/api/v1/trades").param("portfolioId", "3").param("status", "VALIDATED")
                        .param("isin", RELIANCE).header("Authorization", AUDITOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].isin").value(RELIANCE));
    }

    @Test
    void cancelPublishesTradeCancelledAndCannotBeRepeated() throws Exception {
        String ref = "IT-CXL-" + UUID.randomUUID().toString().substring(0, 8);
        long id = id(create(PRIYA, trade(ref, 1, RELIANCE, "BUY", "5", null)));

        mockMvc.perform(post("/api/v1/trades/" + id + "/cancel").header("Authorization", PRIYA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Booked twice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/v1/trades/" + id + "/cancel").header("Authorization", PRIYA))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("Trade " + ref + " cannot move from CANCELLED to CANCELLED"));
        assertThat((Object) JsonPath.read(awaitRecord(Topics.TRADE_CANCELLED, ref).value(), "$.payload.status"))
                .isEqualTo("CANCELLED");
    }

    @Test
    void cancellingABuyThatALaterSellNeedsIsRefused() throws Exception {
        long buy = id(create(ADMIN, trade(null, 3, HDFC, "BUY", "20", null)));
        create(ADMIN, trade(null, 3, HDFC, "SELL", "20", null)).andExpect(jsonPath("$.status").value("VALIDATED"));

        mockMvc.perform(post("/api/v1/trades/" + buy + "/cancel").header("Authorization", ADMIN))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void onlyAdminSettlesManuallyAndDueTradesAutoSettle() throws Exception {
        String manualRef = "IT-STL-" + UUID.randomUUID().toString().substring(0, 8);
        long manual = id(create(PRIYA, trade(manualRef, 1, RELIANCE, "BUY", "7", null)));
        mockMvc.perform(post("/api/v1/trades/" + manual + "/settle").header("Authorization", PRIYA))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/trades/" + manual + "/settle").header("Authorization", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"));
        assertThat((Object) JsonPath.read(awaitRecord(Topics.TRADE_SETTLED, manualRef).value(), "$.payload.status"))
                .isEqualTo("SETTLED");

        // Traded Monday 19-Oct, settles Tuesday 20-Oct: due on Wednesday 21-Oct.
        long due = id(create(PRIYA, trade(null, 1, RELIANCE, "BUY", "3", "2026-10-19")));
        long notDue = id(create(PRIYA, trade(null, 1, RELIANCE, "BUY", "4", null)));
        while (tradeService.settleDueBatch(200) > 0) {
            // settle everything due
        }
        mockMvc.perform(get("/api/v1/trades/" + due).header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.status").value("SETTLED"));
        mockMvc.perform(get("/api/v1/trades/" + notDue).header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.status").value("VALIDATED"));
        mockMvc.perform(get("/api/v1/trades/" + due + "/audit").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$[2].toStatus").value("SETTLED"))
                .andExpect(jsonPath("$[2].changedBy").doesNotExist());
    }

    @Test
    void csvUploadProcessesEachRowIndependently() throws Exception {
        String csv = """
                trade_ref,portfolio_id,isin,side,quantity,price,trade_date,settle_date
                ,1,INE002A01018,BUY,15,2510.25,,
                ,1,INE154A01025,BUY,10,400,,
                ,1,INE002A01018,HOLD,10,400,,
                ,1,INE002A01018,BUY,-5,400,,
                """;
        mockMvc.perform(multipart("/api/v1/trades/upload")
                        .file(new MockMultipartFile("file", "trades.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", PRIYA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.validated").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.failed").value(2))
                .andExpect(jsonPath("$.errors[0].line").value(4));
    }

    private org.springframework.test.web.servlet.ResultActions create(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/trades").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static long id(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return ((Number) JsonPath.read(actions.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private static String trade(String tradeRef, long portfolioId, String isin, String side, String quantity,
                                String tradeDate) {
        return """
                {"tradeRef":%s,"portfolioId":%d,"isin":"%s","side":"%s","quantity":%s,"price":2500.50,"tradeDate":%s}"""
                .formatted(tradeRef == null ? "null" : "\"" + tradeRef + "\"", portfolioId, isin, side, quantity,
                        tradeDate == null ? "null" : "\"" + tradeDate + "\"");
    }

    /** Reads the topic from the beginning until a record for the trade reference appears (or 20 s pass). */
    private static ConsumerRecord<String, String> awaitRecord(String topic, String tradeRef) {
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
                    // Parse rather than match text: the outbox stores payloads as jsonb, which normalises spacing.
                    if (tradeRef.equals(JsonPath.read(record.value(), "$.payload.tradeRef"))) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No " + topic + " event for " + tradeRef + " within 20 s");
    }
}
