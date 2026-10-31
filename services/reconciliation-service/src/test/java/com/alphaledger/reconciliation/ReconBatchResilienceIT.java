package com.alphaledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

import com.alphaledger.reconciliation.repository.ReconRepository;
import com.alphaledger.reconciliation.run.ReconRunService;

/** Failure behaviour of the reconciliation job: restart, retry, skip, stale-run recovery and partition independence. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ReconBatchResilienceIT {

    private static final String OPS = TestTokens.bearer(3, "OPS_ANALYST");
    private static final String HEADER = "trade_ref,isin,trade_date,settle_date,side,quantity,price";
    private static final int ROWS = 5_000;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReconRunService runs;

    @MockitoSpyBean
    private ReconRepository repository;

    @AfterEach
    void clearStubs() {
        reset(repository);
    }

    @Test
    void aRunFailedMidChunkResumesWithoutDuplicatesOrRepeatedWork() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 1);
        String file = settledFile(date, "RST");
        AtomicInteger calls = failFrom(5);

        long runId = id(upload(file, date).andExpect(jsonPath("$.status").value("FAILED")));
        calls.set(Integer.MIN_VALUE);   // the fault is gone

        AtomicInteger resumedWrites = new AtomicInteger();
        doAnswer(i -> {
            resumedWrites.incrementAndGet();
            return i.callRealMethod();
        }).when(repository).insertStaged(anyList());
        mockMvc.perform(post("/api/v1/recon/runs/" + runId + "/restart").header("Authorization", OPS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.error_message").doesNotExist())
                .andExpect(jsonPath("$.total_records").value(ROWS))
                .andExpect(jsonPath("$.price_mismatch").value(50))
                .andExpect(jsonPath("$.matched_count").value(ROWS - 50));

        assertThat(resumedWrites.get()).isBetween(1, 11);   // a full staging pass is 12 chunks
        assertThat(count("custodian_record", runId)).isEqualTo(ROWS);
        assertThat(count("recon_break", runId)).isEqualTo(50);
        assertThat(count("custodian_raw_row", runId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE message_key = ?", Integer.class,
                String.valueOf(runId))).isEqualTo(50);

        mockMvc.perform(post("/api/v1/recon/runs/" + runId + "/restart").header("Authorization", OPS))
                .andExpect(status().isConflict());
    }

    @Test
    void uploadingTheFileOfAFailedRunResumesThatRun() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 2);
        String file = settledFile(date, "UPL");
        AtomicInteger calls = failFrom(1);
        long runId = id(upload(file, date).andExpect(jsonPath("$.status").value("FAILED")));
        calls.set(Integer.MIN_VALUE);

        upload(file, date).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void aTransientWriteFailureIsRetried() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 3);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(i -> {
            if (calls.incrementAndGet() == 1) {
                throw new TransientDataAccessResourceException("injected");
            }
            return i.callRealMethod();
        }).when(repository).insertStaged(anyList());

        upload(settledFile(date, "RTY"), date).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.total_records").value(ROWS));
    }

    @Test
    void aRowTheDatabaseRefusesIsReportedAndTheRestOfTheChunkIsKept() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 4);
        String t = date.minusDays(1).toString();
        String d = date.toString();
        String file = HEADER + "\n"
                + "OK-1,INE002A01018," + t + "," + d + ",BUY,10,100\n"
                + "HUGE,INE002A01018," + t + "," + d + ",BUY,1e30,100\n"
                + "OK-2,INE002A01018," + t + "," + d + ",BUY,10,100\n";

        long runId = id(upload(file, date).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.error_rows").value(1))
                .andExpect(jsonPath("$.total_records").value(3)));

        assertThat(count("custodian_record", runId)).isEqualTo(2);
        mockMvc.perform(get("/api/v1/recon/runs/" + runId + "/errors").header("Authorization", OPS))
                .andExpect(jsonPath("$[0].row_number").value(3));
    }

    @Test
    void realLineNumbersHashRowsAndDuplicatesAcrossPartitionsSurvive() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 5);
        String t = date.minusDays(1).toString();
        StringBuilder file = new StringBuilder("﻿" + HEADER + "\r\n");
        for (int i = 0; i < 100; i++) {
            file.append("DUP-").append(i).append(",INE002A01018,").append(t).append(',').append(date)
                    .append(",BUY,10,100\r\n");
        }
        file.append("\r\n#HASH,INE002A01018,").append(t).append(',').append(date).append(",BUY,10,100\r\n");
        file.append("DUP-0,INE002A01018,").append(t).append(',').append(date).append(",BUY,10,100\r\n");

        long runId = id(upload(file.toString(), date).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.error_rows").value(1))
                .andExpect(jsonPath("$.total_records").value(102)));

        assertThat(jdbc.queryForObject("SELECT row_number FROM custodian_record WHERE run_id = ? AND trade_ref = '#HASH'",
                Integer.class, runId)).isEqualTo(103);
        mockMvc.perform(get("/api/v1/recon/runs/" + runId + "/errors").header("Authorization", OPS))
                .andExpect(jsonPath("$[0].row_number").value(104))
                .andExpect(jsonPath("$[0].error_message").value("duplicate trade_ref + isin + trade_date in the file"));
    }

    @Test
    void aRunLeftRunningByACrashBecomesFailedAndCanRestart() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 6);
        long runId = id(upload(HEADER + "\n", date));
        jdbc.update("UPDATE recon_run SET status = 'RUNNING', started_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(7200)), runId);

        runs.recoverStaleRuns();

        assertThat(jdbc.queryForObject("SELECT status FROM recon_run WHERE id = ?", String.class, runId))
                .isEqualTo("FAILED");
    }

    /** Stored trades matching the file, with a price break on every 100th row. */
    private String settledFile(LocalDate date, String prefix) {
        List<Object[]> trades = new ArrayList<>();
        long base = (long) prefix.hashCode() & 0xFFFFFF;
        StringBuilder file = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < ROWS; i++) {
            trades.add(new Object[] {base * 100_000 + i, prefix + i, 1L, 18L, "INE002A01018", "BUY", BigDecimal.TEN,
                    new BigDecimal("100"), Date.valueOf(date.minusDays(1)), Date.valueOf(date),
                    Timestamp.from(Instant.now())});
            file.append(prefix).append(i).append(",INE002A01018,").append(date.minusDays(1)).append(',').append(date)
                    .append(",BUY,10,").append(i % 100 == 0 ? "101" : "100").append('\n');
        }
        jdbc.batchUpdate("""
                INSERT INTO settled_trade_view (trade_id, trade_ref, portfolio_id, instrument_id, isin, side, quantity,
                                                price, trade_date, settle_date, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", trades);
        return file.toString();
    }

    /** Every staging write from the n-th on fails with a non-retryable, non-skippable error. */
    private AtomicInteger failFrom(int n) {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(i -> {
            if (calls.incrementAndGet() >= n) {
                throw new IllegalStateException("injected");
            }
            return i.callRealMethod();
        }).when(repository).insertStaged(anyList());
        return calls;
    }

    private ResultActions upload(String content, LocalDate date) throws Exception {
        return mockMvc.perform(multipart("/api/v1/recon/runs")
                .file(new MockMultipartFile("file", "f.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8)))
                .param("runDate", date.toString())
                .header("Authorization", OPS));
    }

    private static long id(ResultActions actions) throws Exception {
        return ((Number) JsonPath.read(actions.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private int count(String table, long runId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE run_id = ?", Integer.class, runId);
    }
}
