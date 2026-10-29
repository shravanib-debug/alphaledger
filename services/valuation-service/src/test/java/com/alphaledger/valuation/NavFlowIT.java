package com.alphaledger.valuation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Predicate;

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
import com.alphaledger.common.event.TradeEvent;

/**
 * Full NAV (Phase 4B): deposits, trade.settled moving cash, liabilities, fee accrual and the end-of-day snapshot with
 * NAV = market value + cash - liabilities. Clock fixed to Thursday 15-Oct-2026 11:30 IST; seed prices are 14-Oct.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class NavFlowIT {

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

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void navIsMarketValuePlusCashMinusLiabilitiesAndIsSnapshotted() throws Exception {
        // Portfolio 7: deposit 300 000.00, then BUY 500 Embassy REIT @ 430 settles on 14-Oct (-215 000.00).
        // Seed close 434.91 -> market value 217 455.00. Cash 85 000.00.
        mockMvc.perform(post("/api/v1/portfolios/7/cash").header("Authorization", PM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"DEPOSIT\",\"amount\":300000}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/portfolios/7/cash").header("Authorization", OPS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DEPOSIT\",\"amount\":300000,\"effectiveDate\":\"2026-10-13\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(300000.00));

        String settled = trade(7, 1, "INE041025011", "BUY", "500", "430", "TR-NAV-1");
        sendTrade(settled);
        sendTrade(settled.replaceFirst("\"eventId\":\"[^\"]+\"", "\"eventId\":\"" + UUID.randomUUID() + "\""));
        send(Topics.POSITION_UPDATED, 7, position(7, 1, "INE041025011", "500", "430", 71));
        await("/api/v1/portfolios/7/valuation", body -> JsonPath.<Integer>read(body, "$.details.pricedPositions") == 1
                && ((Number) JsonPath.read(body, "$.cashBalance")).doubleValue() == 85000.00);

        // The redelivered settlement (same trade, new event ID) moved cash once.
        mockMvc.perform(get("/api/v1/portfolios/7/cash").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.balance").value(85000.00))
                .andExpect(jsonPath("$.transactions.length()").value(2))
                .andExpect(jsonPath("$.transactions[0].type").value("TRADE_BUY"))
                .andExpect(jsonPath("$.transactions[0].reference_id").value("TR-NAV-1"))
                .andExpect(jsonPath("$.transactions[0].effective_date").value("2026-10-14"))   // ISO date, no time
                .andExpect(jsonPath("$.transactions[1].effective_date").value("2026-10-13"));

        // Withdrawing more than the balance is refused.
        mockMvc.perform(post("/api/v1/portfolios/7/cash").header("Authorization", OPS)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"WITHDRAWAL\",\"amount\":90000}"))
                .andExpect(status().isUnprocessableContent());

        // Liabilities: an expense payable of 1 000.00 (deleted as an error), then one of 2 500.00 kept.
        String wrong = mockMvc.perform(post("/api/v1/portfolios/7/liabilities").header("Authorization", OPS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPENSE\",\"description\":\"Audit fee\",\"amount\":1000}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(delete("/api/v1/portfolios/7/liabilities/" + JsonPath.read(wrong, "$.id"))
                .header("Authorization", OPS)).andExpect(status().isNoContent());
        String expense = mockMvc.perform(post("/api/v1/portfolios/7/liabilities").header("Authorization", OPS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPENSE\",\"description\":\"Custody fee\",\"amount\":2000}"))
                .andReturn().getResponse().getContentAsString();
        Object expenseId = JsonPath.read(expense, "$.id");
        mockMvc.perform(put("/api/v1/portfolios/7/liabilities/" + expenseId).header("Authorization", OPS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Custody fee Q3\",\"amount\":2500}"))
                .andExpect(jsonPath("$.amount").value(2500.00));

        // Fee accrual at 1% a year: 0.01 x 217 455.00 / 365 = 5.957... -> 5.96. Running it twice charges once.
        mockMvc.perform(post("/api/v1/valuations/fee-accruals").param("date", "2026-10-15").header("Authorization", ADMIN))
                .andExpect(jsonPath("$.portfoliosCharged").value(1));
        mockMvc.perform(post("/api/v1/valuations/fee-accruals").param("date", "2026-10-15").header("Authorization", ADMIN))
                .andExpect(jsonPath("$.portfoliosCharged").value(0));

        // NAV = 217 455.00 + 85 000.00 - (2 500.00 + 5.96) = 299 949.04.
        mockMvc.perform(get("/api/v1/portfolios/7/valuation").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.valuationBasis").value("FULL_NAV"))
                .andExpect(jsonPath("$.marketValue").value(217455.00))
                .andExpect(jsonPath("$.cashBalance").value(85000.00))
                .andExpect(jsonPath("$.totalLiabilities").value(2505.96))
                .andExpect(jsonPath("$.nav").value(299949.04));
        // On 12-Oct nothing had happened yet: the deposit is effective 13-Oct and the trade settled 14-Oct.
        mockMvc.perform(get("/api/v1/portfolios/7/valuation").param("asOf", "2026-10-12").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.cashBalance").value(0.00))
                .andExpect(jsonPath("$.totalLiabilities").value(0.00));

        mockMvc.perform(post("/api/v1/valuations/eod-runs").param("date", "2026-10-15").header("Authorization", ADMIN))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mockMvc.perform(get("/api/v1/portfolios/7/valuation/history").param("from", "2026-10-01")
                        .header("Authorization", AUDITOR))
                .andExpect(jsonPath("$[0].valuation_basis").value("FULL_NAV"))
                .andExpect(jsonPath("$[0].as_of_date").value("2026-10-15"))
                .andExpect(jsonPath("$[0].cash_balance").value(85000.00))
                .andExpect(jsonPath("$[0].total_liabilities").value(2505.96))
                .andExpect(jsonPath("$[0].nav").value(299949.04));

        // Paying the expense moves it from liabilities to cash: NAV is unchanged.
        mockMvc.perform(post("/api/v1/portfolios/7/liabilities/" + expenseId + "/pay").header("Authorization", OPS))
                .andExpect(jsonPath("$.status").value("PAID"));
        mockMvc.perform(get("/api/v1/portfolios/7/valuation").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.cashBalance").value(82500.00))
                .andExpect(jsonPath("$.totalLiabilities").value(5.96))
                .andExpect(jsonPath("$.nav").value(299949.04));
        mockMvc.perform(post("/api/v1/portfolios/7/liabilities/" + expenseId + "/pay").header("Authorization", OPS))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(get("/api/v1/portfolios/7/liabilities").param("status", "OPEN").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("MANAGEMENT_FEE"));
    }

    @Test
    void borrowingBringsInCashAndLiabilitiesCanExceedAssets() throws Exception {
        // Portfolio 8 borrows 100 000.00: cash +100 000.00 and a liability of 100 000.00 -> NAV 0.
        // Withdrawing 100 000.00 leaves NAV = 0 + 0 - 100 000.00 = -100 000.00, reported as it is.
        String borrowing = mockMvc.perform(post("/api/v1/portfolios/8/liabilities").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BORROWING\",\"description\":\"Bank line\",\"amount\":100000}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(get("/api/v1/portfolios/8/valuation").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.cashBalance").value(100000.00))
                .andExpect(jsonPath("$.nav").value(0.00));

        mockMvc.perform(post("/api/v1/portfolios/8/cash").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"WITHDRAWAL\",\"amount\":100000}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/portfolios/8/valuation").header("Authorization", AUDITOR))
                .andExpect(jsonPath("$.nav").value(-100000.00));

        Object id = JsonPath.read(borrowing, "$.id");
        mockMvc.perform(delete("/api/v1/portfolios/8/liabilities/" + id).header("Authorization", ADMIN))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(put("/api/v1/portfolios/8/liabilities/" + id).header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"Bank line\",\"amount\":50000}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void theCashLedgerIsAppendOnly() {
        jdbc.update("""
                INSERT INTO cash_transaction (portfolio_id, type, amount, effective_date, occurred_at)
                VALUES (9, 'DEPOSIT', 10, DATE '2026-10-15', now())
                """);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.update("UPDATE cash_transaction SET amount = 20 WHERE portfolio_id = 9"))
                .hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.update("DELETE FROM cash_transaction WHERE portfolio_id = 9"))
                .hasMessageContaining("append-only");
    }

    private String trade(long portfolioId, long instrumentId, String isin, String side, String qty, String price,
                         String ref) {
        TradeEvent payload = new TradeEvent(70, ref, portfolioId, instrumentId, isin, side, new BigDecimal(qty),
                new BigDecimal(price), "INR", LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 14), "SETTLED");
        return jsonMapper.writeValueAsString(new EventEnvelope<>(UUID.randomUUID(), EventTypes.TRADE_SETTLED, 1,
                Instant.now(), "it", "trade-service", payload));
    }

    private String position(long portfolioId, long instrumentId, String isin, String qty, String avg, long lastTradeId) {
        PositionEvent payload = new PositionEvent(portfolioId, instrumentId, isin, new BigDecimal(qty),
                new BigDecimal(avg), BigDecimal.ZERO, lastTradeId, Instant.now());
        return jsonMapper.writeValueAsString(new EventEnvelope<>(UUID.randomUUID(), EventTypes.POSITION_UPDATED, 1,
                Instant.now(), "it", "position-service", payload));
    }

    private void sendTrade(String value) throws Exception {
        send(Topics.TRADE_SETTLED, JsonPath.<Integer>read(value, "$.payload.portfolioId"), value);
    }

    private void send(String topic, long key, String value) throws Exception {
        kafka.send(topic, String.valueOf(key), value).get();
    }

    private void await(String url, Predicate<String> done) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            String body = mockMvc.perform(get(url).header("Authorization", AUDITOR))
                    .andReturn().getResponse().getContentAsString();
            if (done.test(body)) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError(url + " not ready within 30 s");
    }
}
