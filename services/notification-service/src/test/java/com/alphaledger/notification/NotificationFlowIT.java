package com.alphaledger.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.json.JsonMapper;

import com.alphaledger.common.event.EventEnvelope;
import com.alphaledger.common.event.EventTypes;
import com.alphaledger.common.event.LimitBreachEvent;
import com.alphaledger.common.event.ReconBreakEvent;
import com.alphaledger.common.event.Topics;
import com.alphaledger.common.web.CorrelationId;

/**
 * Notifications end to end (Postgres, Kafka, MailHog) and the Phase 6 failure-injection checks: poison messages go to
 * the dead-letter topic without blocking the consumer, dead letters can be replayed, and a stopped consumer loses
 * nothing. Tests share one database, so each uses its own portfolio IDs and break IDs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class NotificationFlowIT {

    private static final String ADMIN = TestTokens.bearer(1, "ADMIN");
    private static final String PM = TestTokens.bearer(2, "PM");
    private static final String OPS = TestTokens.bearer(3, "OPS_ANALYST");
    private static final String AUDITOR = TestTokens.bearer(4, "AUDITOR");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2");

    @Container
    static final GenericContainer<?> MAILHOG = new GenericContainer<>("mailhog/mailhog:v1.0.1")
            .withExposedPorts(1025, 8025);

    @DynamicPropertySource
    static void mail(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILHOG::getHost);
        registry.add("spring.mail.port", () -> MAILHOG.getMappedPort(1025));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Test
    void aBreakNotifiesOpsByAppAndEmailOnceAndEachUserHasTheirOwnReadState() throws Exception {
        String created = envelope(EventTypes.RECON_BREAK_CREATED, breakEvent(9001, "QUANTITY_MISMATCH", "OPEN"));
        send(Topics.RECON_BREAK_CREATED, "41", created);
        send(Topics.RECON_BREAK_CREATED, "41", created);   // redelivery: same event ID
        await(() -> count("reference = '9001'") == 1);

        String list = mockMvc.perform(get("/api/v1/notifications").header("Authorization", OPS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.reference == '9001')].title")
                        .value("Reconciliation break: Quantity mismatch on TR-9001"))
                .andExpect(jsonPath("$[?(@.reference == '9001')].audience_role").value("OPS_ANALYST"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.<java.util.List<Object>>read(list, "$[?(@.reference == '9001')].id").get(0))
                .longValue();

        // PMs do not see Ops notifications; Admin and Auditor see everything.
        mockMvc.perform(get("/api/v1/notifications").header("Authorization", PM))
                .andExpect(jsonPath("$[?(@.reference == '9001')]").isEmpty());
        mockMvc.perform(get("/api/v1/notifications").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$[?(@.reference == '9001')]").isNotEmpty());
        mockMvc.perform(post("/api/v1/notifications/" + id + "/read").header("Authorization", PM))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/notifications/" + id + "/read").header("Authorization", OPS))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/notifications").param("unreadOnly", "true").header("Authorization", OPS))
                .andExpect(jsonPath("$[?(@.reference == '9001')]").isEmpty());
        mockMvc.perform(get("/api/v1/notifications").param("unreadOnly", "true").header("Authorization", ADMIN))
                .andExpect(jsonPath("$[?(@.reference == '9001')]").isNotEmpty());   // read state is per user
        mockMvc.perform(post("/api/v1/notifications/read-all").header("Authorization", ADMIN))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", ADMIN))
                .andExpect(jsonPath("$.unread").value(0));

        // Email to the Ops recipient, through MailHog.
        await(() -> mailhog().contains("Quantity mismatch on TR-9001"));
        assertThat(mailhog()).contains("rohan.ops@alphaledger.test");
        assertThat(jdbc.queryForObject("SELECT email_status FROM notification WHERE reference = '9001'", String.class))
                .isEqualTo("SENT");
    }

    @Test
    void aLimitBreachNotifiesPmsAndTheDigestCountsOpenBreaks() throws Exception {
        send(Topics.LIMIT_BREACHED, "42", envelope(EventTypes.LIMIT_BREACHED, limitEvent(42)));
        await(() -> count("type = 'LIMIT_BREACH' AND portfolio_id = 42") == 1);
        mockMvc.perform(get("/api/v1/notifications").header("Authorization", PM))
                .andExpect(jsonPath("$[?(@.portfolio_id == 42)].title")
                        .value("Limit breached: REAL_ESTATE at 72.50% in portfolio 42"))
                .andExpect(jsonPath("$[?(@.portfolio_id == 42)].severity").value("CRITICAL"));

        // Two breaks open, one resolved: the digest reports one.
        send(Topics.RECON_BREAK_CREATED, "43", envelope(EventTypes.RECON_BREAK_CREATED,
                breakEvent(9101, "PRICE_MISMATCH", "OPEN")));
        send(Topics.RECON_BREAK_CREATED, "43", envelope(EventTypes.RECON_BREAK_CREATED,
                breakEvent(9102, "MISSING_EXTERNAL", "OPEN")));
        send(Topics.RECON_BREAK_RESOLVED, "9102", envelope(EventTypes.RECON_BREAK_RESOLVED,
                breakEvent(9102, "MISSING_EXTERNAL", "RESOLVED")));
        await(() -> jdbc.queryForObject("SELECT count(*) FROM open_break WHERE break_id IN (9101, 9102)",
                Integer.class) == 1 && count("reference = '9102'") == 1);

        mockMvc.perform(post("/api/v1/notifications/digests").header("Authorization", OPS))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/notifications/digests").header("Authorization", ADMIN))
                .andExpect(jsonPath("$.created").value(true));
        String digest = jdbc.queryForObject("SELECT message FROM notification WHERE type = 'OPEN_BREAKS_DIGEST' "
                + "ORDER BY id DESC LIMIT 1", String.class);
        assertThat(digest).contains("Price mismatch: 1").doesNotContain("Missing external");
    }

    @Test
    void poisonMessagesAreDeadLetteredWithoutBlockingTheConsumerAndCanBeReplayed() throws Exception {
        // Not JSON, and an envelope with no payload: neither can ever succeed, so both skip the retries.
        send(Topics.LIMIT_BREACHED, "44", "this is not json");
        send(Topics.LIMIT_BREACHED, "44", "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"LIMIT_BREACHED\"}");
        // A valid event behind them on the same partition (same key) is still processed.
        send(Topics.LIMIT_BREACHED, "44", envelope(EventTypes.LIMIT_BREACHED, limitEvent(44)));
        await(() -> count("type = 'LIMIT_BREACH' AND portfolio_id = 44") == 1);

        await(() -> pendingDeadLetters(Topics.LIMIT_BREACHED).contains("this is not json"));
        String pending = pendingDeadLetters(Topics.LIMIT_BREACHED);
        assertThat(JsonPath.<Integer>read(pending, "$.length()")).isEqualTo(2);
        assertThat(JsonPath.<String>read(pending, "$[0].originalTopic")).isEqualTo(Topics.LIMIT_BREACHED);
        assertThat(JsonPath.<String>read(pending, "$[0].exception")).isNotBlank();

        mockMvc.perform(get("/api/v1/ops/dead-letters/" + Topics.LIMIT_BREACHED).header("Authorization", OPS))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/ops/dead-letters/unknown.topic").header("Authorization", ADMIN))
                .andExpect(status().isBadRequest());

        // A good event that was dead-lettered during an outage: replaying it delivers it.
        kafka.send(new ProducerRecord<>(Topics.LIMIT_BREACHED + ".DLT", "45",
                envelope(EventTypes.LIMIT_BREACHED, limitEvent(45)))).get();
        mockMvc.perform(post("/api/v1/ops/dead-letters/" + Topics.LIMIT_BREACHED + "/replay")
                        .header("Authorization", ADMIN))
                .andExpect(jsonPath("$.replayed").value(3));
        await(() -> count("type = 'LIMIT_BREACH' AND portfolio_id = 45") == 1);
        // The two poison messages failed again and are back in the dead-letter topic, ready to inspect.
        await(() -> JsonPath.<Integer>read(pendingDeadLetters(Topics.LIMIT_BREACHED), "$.length()") == 2);
    }

    @Test
    void aStoppedConsumerLosesNoEventsAndCatchesUpOnRestart() throws Exception {
        var container = listeners.getListenerContainer("notification-limits");
        container.stop();
        await(() -> !container.isRunning());

        for (long portfolio = 46; portfolio <= 48; portfolio++) {
            send(Topics.LIMIT_BREACHED, String.valueOf(portfolio),
                    envelope(EventTypes.LIMIT_BREACHED, limitEvent(portfolio)));
        }
        Thread.sleep(1500);
        assertThat(count("portfolio_id BETWEEN 46 AND 48")).isZero();

        container.start();
        await(() -> count("portfolio_id BETWEEN 46 AND 48") == 3);
    }

    @Test
    void theCorrelationIdHeaderReachesTheConsumer() throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.LIMIT_BREACHED, "49",
                envelope(EventTypes.LIMIT_BREACHED, limitEvent(49)));
        record.headers().add(CorrelationId.HEADER, "corr-49".getBytes());
        kafka.send(record).get();
        await(() -> count("portfolio_id = 49") == 1);   // the interceptor accepted the header; logs carry corr-49
    }

    private String pendingDeadLetters(String topic) throws Exception {
        return mockMvc.perform(get("/api/v1/ops/dead-letters/" + topic).header("Authorization", ADMIN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private int count(String where) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE " + where, Integer.class);
    }

    private static ReconBreakEvent breakEvent(long breakId, String type, String status) {
        return new ReconBreakEvent(breakId, 7, type, "TR-" + breakId, "INE041025011", LocalDate.of(2026, 10, 15), 2L,
                new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("434.91"), new BigDecimal("434.91"),
                status, "RESOLVED".equals(status) ? "BOOKING_CORRECTED" : null, null);
    }

    private static LimitBreachEvent limitEvent(long portfolioId) {
        return new LimitBreachEvent(portfolioId, "REAL_ESTATE", new BigDecimal("72.5"), new BigDecimal("60"),
                new BigDecimal("385745.00"), LocalDate.of(2026, 10, 15));
    }

    private String envelope(String type, Object payload) {
        return jsonMapper.writeValueAsString(new EventEnvelope<>(UUID.randomUUID(), type, 1, Instant.now(), "it",
                "it", payload));
    }

    private void send(String topic, String key, String value) throws Exception {
        kafka.send(topic, key, value).get();
    }

    private static String mailhog() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + MAILHOG.getHost() + ":"
                + MAILHOG.getMappedPort(8025) + "/api/v2/messages")).build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        }
    }

    private static void await(ThrowingCondition done) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(40));
        while (Instant.now().isBefore(deadline)) {
            if (done.test()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Condition not met within 40 s");
    }

    @FunctionalInterface
    private interface ThrowingCondition {
        boolean test() throws Exception;
    }
}
