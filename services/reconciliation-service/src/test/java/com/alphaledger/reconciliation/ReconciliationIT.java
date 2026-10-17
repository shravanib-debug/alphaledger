package com.alphaledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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

/**
 * Reconciliation end to end on Postgres and Kafka: trade.settled events build the internal side, a custodian file with
 * exactly 3 missing-internal, 3 missing-external, 4 quantity and 4 price breaks produces exactly those breaks
 * (the Phase 5 Definition of Done), and every break can be worked through to resolution with a full audit trail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconciliationIT {

    private static final String ADMIN = TestTokens.bearer(1, "ADMIN");
    private static final String PM = TestTokens.bearer(2, "PM");
    private static final String OPS = TestTokens.bearer(3, "OPS_ANALYST");
    private static final String AUDITOR = TestTokens.bearer(4, "AUDITOR");
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 10, 14);
    private static final LocalDate RUN_DATE = LocalDate.of(2026, 10, 15);
    private static final String HEADER = "trade_ref,isin,trade_date,settle_date,side,quantity,price";

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
    private JdbcTemplate jdbc;

    private final List<TradeEvent> settled = new ArrayList<>();

    /** 20 trades settled on 15-Oct, delivered as trade.settled events (plus one duplicate delivery). */
    @BeforeAll
    void publishSettledTrades() throws Exception {
        for (int i = 1; i <= 20; i++) {
            TradeEvent t = new TradeEvent(1000 + i, "DEMO-" + String.format("%02d", i), 1 + i % 3, 18,
                    "INE002A01018", i % 5 == 0 ? "SELL" : "BUY", new BigDecimal(10 * i), new BigDecimal("1174.60"),
                    "INR", TRADE_DATE, RUN_DATE, "SETTLED");
            settled.add(t);
            String event = envelope(t);
            kafka.send(Topics.TRADE_SETTLED, String.valueOf(t.portfolioId()), event).get();
            if (i == 1) {
                kafka.send(Topics.TRADE_SETTLED, String.valueOf(t.portfolioId()), event).get();   // redelivery
            }
        }
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (count("SELECT count(*) FROM settled_trade_view WHERE settle_date = '2026-10-15'") < 20) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("settled trades not consumed within 30 s");
            }
            Thread.sleep(300);
        }
    }

    @Test
    void injectedFileProducesExactly3Missing3Missing4Quantity4PriceBreaks() throws Exception {
        String body = upload(injectedFile(), "custodian-2026-10-15.csv", RUN_DATE, OPS)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.internal_trades").value(20))
                .andExpect(jsonPath("$.missing_internal").value(3))
                .andExpect(jsonPath("$.missing_external").value(3))
                .andExpect(jsonPath("$.qty_mismatch").value(4))
                .andExpect(jsonPath("$.price_mismatch").value(4))
                .andExpect(jsonPath("$.break_count").value(14))
                .andExpect(jsonPath("$.matched_count").value(9))   // 20 - 3 dropped - 4 - 4
                .andExpect(jsonPath("$.match_rate_pct").value(39.13)) // 9 / (9 + 14)
                .andReturn().getResponse().getContentAsString();
        long runId = ((Number) JsonPath.read(body, "$.id")).longValue();

        mockMvc.perform(get("/api/v1/recon/breaks").param("runId", String.valueOf(runId)).param("type", "QTY_MISMATCH")
                        .header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].age_days").value(0))
                .andExpect(jsonPath("$[0].overdue").value(false));

        // Re-uploading the same file for the same date returns the same run and creates nothing new (FR-REC-11).
        upload(injectedFile(), "custodian-again.csv", RUN_DATE, OPS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId));
        assertThat(count("SELECT count(*) FROM recon_break WHERE run_id = " + runId)).isEqualTo(14);

        ConsumerRecord<String, String> created = awaitRecord(Topics.RECON_BREAK_CREATED,
                v -> JsonPath.<Integer>read(v, "$.payload.runId") == runId);
        assertThat(created.key()).isEqualTo(String.valueOf(runId));
    }

    @Test
    void aBreakIsWorkedFromOpenToResolvedWithAFullAuditTrail() throws Exception {
        long runId = runId(upload(fileWithOnePriceBreak(), "one-break.csv", RUN_DATE, ADMIN));
        long breakId = jdbc.queryForObject("SELECT id FROM recon_break WHERE run_id = ? AND break_type = 'PRICE_MISMATCH'",
                Long.class, runId);

        mockMvc.perform(post("/api/v1/recon/breaks/" + breakId + "/resolve").header("Authorization", OPS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ACCEPT_EXTERNAL\",\"comment\":\"Too early\"}"))
                .andExpect(status().isUnprocessableContent());   // must be reviewed first
        mockMvc.perform(post("/api/v1/recon/breaks/" + breakId + "/assign").header("Authorization", AUDITOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Mine\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/recon/breaks/" + breakId + "/assign").header("Authorization", OPS)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":3,\"comment\":\"Checking the contract note\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.assigned_to").value(3));
        mockMvc.perform(post("/api/v1/recon/breaks/" + breakId + "/resolve").header("Authorization", OPS)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"ADJUST_INTERNAL\",\"comment\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/recon/breaks/" + breakId + "/escalate").header("Authorization", OPS)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Price differs by 2 rupees\"}"))
                .andExpect(jsonPath("$.status").value("ESCALATED"));
        mockMvc.perform(post("/api/v1/recon/breaks/" + breakId + "/resolve").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ACCEPT_EXTERNAL\",\"comment\":\"Custodian price confirmed by broker\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolution_type").value("ACCEPT_EXTERNAL"))
                .andExpect(jsonPath("$.resolved_at").exists());
        mockMvc.perform(post("/api/v1/recon/breaks/" + breakId + "/assign").header("Authorization", OPS)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Reopen?\"}"))
                .andExpect(status().isUnprocessableContent());   // resolved breaks are never reopened

        mockMvc.perform(get("/api/v1/recon/breaks/" + breakId + "/audit").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].action").value("CREATED"))
                .andExpect(jsonPath("$[1].action").value("ASSIGN"))
                .andExpect(jsonPath("$[1].performed_by").value(3))
                .andExpect(jsonPath("$[2].action").value("ESCALATE"))
                .andExpect(jsonPath("$[3].action").value("ACCEPT_EXTERNAL"))
                .andExpect(jsonPath("$[3].performed_by").value(1));

        ConsumerRecord<String, String> resolved = awaitRecord(Topics.RECON_BREAK_RESOLVED,
                v -> JsonPath.<Integer>read(v, "$.payload.breakId") == breakId);
        assertThat((Object) JsonPath.read(resolved.value(), "$.payload.resolutionType")).isEqualTo("ACCEPT_EXTERNAL");
    }

    @Test
    void badRowsAreReportedAndBadFilesRefused() throws Exception {
        String file = HEADER + "\n"
                + "DEMO-01,INE002A01018,2026-10-14,2026-10-15,BUY,10,1174.60\n"
                + "BAD-1,NOT-AN-ISIN,2026-10-14,2026-10-15,BUY,10,1\n"
                + "BAD-2,INE002A01018,2026-10-14,2026-10-16,BUY,10,1\n"
                + "BAD-3,INE002A01018,14/10/2026,2026-10-15,BUY,10,1\n"
                + "BAD-4,INE002A01018,2026-10-14,2026-10-15,HOLD,10,1\n"
                + "DEMO-01,INE002A01018,2026-10-14,2026-10-15,BUY,10,1174.60\n";
        long runId = runId(upload(file, "bad-rows.csv", RUN_DATE, OPS).andExpect(jsonPath("$.error_rows").value(5)));

        mockMvc.perform(get("/api/v1/recon/runs/" + runId + "/errors").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[1].error_message").value("settle_date 2026-10-16 is not the run date 2026-10-15"))
                .andExpect(jsonPath("$[4].error_message").value("duplicate trade_ref + isin + trade_date in the file"));

        upload("wrong,header\n", "x.csv", RUN_DATE, OPS).andExpect(status().isBadRequest());
        upload(HEADER + "\n", "future.csv", LocalDate.of(2030, 1, 1), OPS).andExpect(status().isBadRequest());
        upload(HEADER + "\n", "pm.csv", RUN_DATE, PM).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/recon/breaks").header("Authorization", PM)).andExpect(status().isForbidden());
    }

    @Test
    void tenThousandRowsReconcileInUnder30Seconds() throws Exception {
        LocalDate perfDate = LocalDate.of(2026, 10, 13);
        List<Object[]> rows = new ArrayList<>();
        StringBuilder file = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < 10_000; i++) {
            rows.add(new Object[] {500_000L + i, "PERF-" + i, 1L, 18L, "INE002A01018", "BUY", BigDecimal.TEN,
                    new BigDecimal("100"), Date.valueOf(perfDate.minusDays(1)), Date.valueOf(perfDate),
                    Timestamp.from(Instant.now())});
            file.append("PERF-").append(i).append(",INE002A01018,2026-10-12,2026-10-13,BUY,10,")
                    .append(i % 100 == 0 ? "101" : "100").append('\n');
        }
        jdbc.batchUpdate("""
                INSERT INTO settled_trade_view (trade_id, trade_ref, portfolio_id, instrument_id, isin, side, quantity,
                                                price, trade_date, settle_date, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows);

        long started = System.nanoTime();
        upload(file.toString(), "perf.csv", perfDate, OPS)
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.total_records").value(10_000))
                .andExpect(jsonPath("$.price_mismatch").value(100))
                .andExpect(jsonPath("$.matched_count").value(9_900));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(30));
    }

    /** 20 settled trades: 3 left out (missing external), 4 quantity and 4 price changes, 3 unknown (missing internal). */
    private String injectedFile() {
        StringBuilder file = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < settled.size(); i++) {
            TradeEvent t = settled.get(i);
            if (i < 3) {
                continue;
            }
            BigDecimal quantity = i < 7 ? t.quantity().add(BigDecimal.ONE) : t.quantity();
            BigDecimal price = i >= 7 && i < 11 ? t.price().add(new BigDecimal("2.50")) : t.price();
            file.append(row(t.tradeRef(), t.tradeDate(), t.side(), quantity, price));
        }
        for (int i = 1; i <= 3; i++) {
            file.append(row("CUST-ONLY-" + i, TRADE_DATE, "BUY", BigDecimal.TEN, new BigDecimal("1174.60")));
        }
        return file.toString();
    }

    private String fileWithOnePriceBreak() {
        StringBuilder file = new StringBuilder(HEADER).append('\n');
        settled.forEach(t -> file.append(row(t.tradeRef(), t.tradeDate(), t.side(), t.quantity(),
                t.tradeRef().equals("DEMO-05") ? new BigDecimal("1176.60") : t.price())));
        return file.toString();
    }

    private static String row(String ref, LocalDate tradeDate, String side, BigDecimal quantity, BigDecimal price) {
        return ref + ",INE002A01018," + tradeDate + "," + RUN_DATE + "," + side + "," + quantity.toPlainString() + ","
                + price.toPlainString() + "\n";
    }

    private ResultActions upload(String content, String name, LocalDate runDate, String token) throws Exception {
        return mockMvc.perform(multipart("/api/v1/recon/runs")
                .file(new MockMultipartFile("file", name, "text/csv", content.getBytes(StandardCharsets.UTF_8)))
                .param("runDate", runDate.toString())
                .header("Authorization", token));
    }

    private static long runId(ResultActions actions) throws Exception {
        return ((Number) JsonPath.read(actions.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String envelope(TradeEvent t) {
        return jsonMapper.writeValueAsString(new EventEnvelope<>(UUID.randomUUID(), EventTypes.TRADE_SETTLED, 1,
                Instant.now(), "it", "trade-service", t));
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
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
