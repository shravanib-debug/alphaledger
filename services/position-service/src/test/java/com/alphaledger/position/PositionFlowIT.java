package com.alphaledger.position;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import com.alphaledger.common.event.Topics;
import com.alphaledger.common.event.TradeEvent;
import com.alphaledger.position.batch.PositionSnapshotJobConfig.PositionSnapshotScheduler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** trade events on Kafka → positions in Postgres → position.updated on Kafka, with real containers. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class PositionFlowIT {

    private static final String AUDITOR = TestTokens.bearer(4, "AUDITOR");
    private static final LocalDate D1 = LocalDate.of(2026, 10, 12);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 13);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private PositionSnapshotScheduler snapshots;

    @Test
    void buysAndAPartialSellBuildTheHoldingAndPublishPositionUpdated() throws Exception {
        send(Topics.TRADE_CREATED, trade(101, 10, 18, "BUY", "200", "500", D1));
        send(Topics.TRADE_CREATED, trade(102, 10, 18, "SELL", "80", "560", D2));

        Map<String, Object> position = awaitPosition(10, 18, p -> new BigDecimal(p.get("quantity").toString())
                .compareTo(new BigDecimal("120")) == 0);
        assertThat(new BigDecimal(position.get("avgCost").toString())).isEqualByComparingTo("500");
        assertThat(new BigDecimal(position.get("realizedPnl").toString())).isEqualByComparingTo("4800");

        ConsumerRecord<String, String> published = awaitRecord(Topics.POSITION_UPDATED,
                value -> JsonPath.<Integer>read(value, "$.payload.lastTradeId") == 102);
        assertThat(published.key()).isEqualTo("10");
        assertThat((Object) JsonPath.read(published.value(), "$.eventType")).isEqualTo("POSITION_UPDATED");
        assertThat((Object) JsonPath.read(published.value(), "$.producer")).isEqualTo("position-service");
    }

    @Test
    void aRedeliveredEventIsAppliedOnlyOnce() throws Exception {
        String sameEvent = trade(201, 11, 19, "BUY", "50", "700", D1);
        send(Topics.TRADE_CREATED, sameEvent);
        send(Topics.TRADE_CREATED, sameEvent);
        send(Topics.TRADE_CREATED, trade(202, 11, 19, "BUY", "10", "710", D2));   // marker: after both copies

        Map<String, Object> position = awaitPosition(11, 19, p -> ((Number) p.get("lastTradeId")).longValue() == 202);
        assertThat(new BigDecimal(position.get("quantity").toString())).isEqualByComparingTo("60");
    }

    @Test
    void cancellingABuyReversesItExactly() throws Exception {
        send(Topics.TRADE_CREATED, trade(301, 12, 20, "BUY", "100", "1000", D1));
        String second = trade(302, 12, 20, "BUY", "100", "1200", D2);
        send(Topics.TRADE_CREATED, second);
        awaitPosition(12, 20, p -> new BigDecimal(p.get("quantity").toString()).compareTo(new BigDecimal("200")) == 0);

        send(Topics.TRADE_CANCELLED, withNewEventId(second, "TRADE_CANCELLED", "CANCELLED"));

        Map<String, Object> position = awaitPosition(12, 20,
                p -> new BigDecimal(p.get("quantity").toString()).compareTo(new BigDecimal("100")) == 0);
        assertThat(new BigDecimal(position.get("avgCost").toString())).isEqualByComparingTo("1000");
    }

    @Test
    void dailySnapshotRecordsTheHoldingsForTheDate() throws Exception {
        send(Topics.TRADE_CREATED, trade(401, 13, 21, "BUY", "5", "1088", D1));
        awaitPosition(13, 21, p -> true);

        snapshots.run(LocalDate.of(2026, 10, 14));
        snapshots.run(LocalDate.of(2026, 10, 14));   // a completed date is not run twice

        String body = mockMvc.perform(get("/api/v1/portfolios/13/positions/snapshots").param("date", "2026-10-14")
                .header("Authorization", AUDITOR)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(body, "$")).hasSize(1);
        assertThat((Object) JsonPath.read(body, "$[0].isin")).isEqualTo("INE009A01021");
    }

    @Test
    void positionsNeedAToken() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/portfolios/10/positions")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    private void send(String topic, String value) throws Exception {
        String key = JsonPath.read(value, "$.payload.portfolioId").toString();
        kafka.send(topic, key, value).get();
    }

    private String trade(long tradeId, long portfolioId, long instrumentId, String side, String qty, String price,
                         LocalDate tradeDate) {
        String isin = instrumentId == 21 ? "INE009A01021" : "INE00000000" + (instrumentId % 10);
        TradeEvent payload = new TradeEvent(tradeId, "TRD-IT-" + tradeId, portfolioId, instrumentId, isin, side,
                new BigDecimal(qty), new BigDecimal(price), "INR", tradeDate, tradeDate.plusDays(1), "VALIDATED");
        return jsonMapper.writeValueAsString(new EventEnvelope<>(UUID.randomUUID(), EventTypes.TRADE_CREATED, 1,
                Instant.now(), "it-" + tradeId, "trade-service", payload));
    }

    private String withNewEventId(String event, String eventType, String status) {
        String withType = event.replace("\"" + EventTypes.TRADE_CREATED + "\"", "\"" + eventType + "\"")
                .replace("\"VALIDATED\"", "\"" + status + "\"");
        String oldId = JsonPath.read(event, "$.eventId");
        return withType.replace(oldId, UUID.randomUUID().toString());
    }

    private Map<String, Object> awaitPosition(long portfolioId, long instrumentId, Predicate<Map<String, Object>> done)
            throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            String body = mockMvc.perform(get("/api/v1/portfolios/" + portfolioId + "/positions")
                    .header("Authorization", AUDITOR)).andReturn().getResponse().getContentAsString();
            List<Map<String, Object>> rows = JsonPath.read(body, "$[?(@.instrumentId == " + instrumentId + ")]");
            if (!rows.isEmpty() && done.test(rows.get(0))) {
                return rows.get(0);
            }
            Thread.sleep(300);
        }
        throw new AssertionError("Position " + portfolioId + "/" + instrumentId + " not as expected within 30 s");
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
