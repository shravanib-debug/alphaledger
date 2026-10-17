package com.alphaledger.reconciliation.run;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.error.NotFoundException;
import com.alphaledger.common.error.ValidationException;
import com.alphaledger.reconciliation.repository.ReconRepository;

/**
 * Starts reconciliation runs (FR-REC-1, FR-REC-6, FR-REC-11). The file is stored with its SHA-256 first; the same file
 * for the same date and custodian returns the existing run instead of creating duplicate breaks. The batch job runs
 * synchronously, so the response already carries the run summary.
 */
@Service
public class ReconRunService {

    private final ReconRepository repository;
    private final JobOperator jobOperator;
    private final Job reconciliationJob;
    private final MarketClock marketClock;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ReconRunService(ReconRepository repository, JobOperator jobOperator, Job reconciliationJob,
                           MarketClock marketClock, Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.jobOperator = jobOperator;
        this.reconciliationJob = reconciliationJob;
        this.marketClock = marketClock;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public record RunOutcome(Map<String, Object> run, boolean duplicate) {
    }

    public RunOutcome start(byte[] content, String fileName, LocalDate runDate, String source, Long userId) {
        if (runDate.isAfter(marketClock.today())) {
            throw new ValidationException("runDate: cannot be in the future");
        }
        String header = new String(content, StandardCharsets.UTF_8).replace("﻿", "").split("\\r?\\n", 2)[0];
        if (!CustodianRowValidator.HEADER.equals(header.strip())) {
            throw new ValidationException("file: first line must be '" + CustodianRowValidator.HEADER + "'");
        }
        String checksum = sha256(content);
        var existing = repository.findRun(runDate, source, checksum);
        if (existing.isPresent()) {
            return new RunOutcome(summary(existing.get()), true);
        }
        Long runId = transaction.execute(status -> {
            long id = repository.insertRun(runDate, source, fileName, checksum, userId, clock.instant());
            repository.insertFile(id, content);
            return id;
        });
        try {
            JobExecution execution = jobOperator.start(reconciliationJob, new JobParametersBuilder()
                    .addLong("runId", runId).addLocalDate("runDate", runDate).toJobParameters());
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                String message = execution.getAllFailureExceptions().stream().map(Throwable::getMessage)
                        .findFirst().orElse(execution.getStatus().name());
                repository.failRun(runId, message, clock.instant());
            }
        } catch (Exception ex) {
            repository.failRun(runId, ex.getMessage(), clock.instant());
        }
        return new RunOutcome(summary(runId), false);
    }

    public Map<String, Object> summary(long runId) {
        Map<String, Object> run = new LinkedHashMap<>(repository.run(runId)
                .orElseThrow(() -> new NotFoundException("Reconciliation run " + runId + " not found")));
        int matched = ((Number) run.get("matched_count")).intValue();
        int breaks = ((Number) run.get("break_count")).intValue();
        run.put("match_rate_pct", matched + breaks == 0 ? null
                : Math.round(10_000.0 * matched / (matched + breaks)) / 100.0);
        return run;
    }

    public List<Map<String, Object>> runs(int limit) {
        return repository.runs(limit);
    }

    public List<Map<String, Object>> errors(long runId) {
        summary(runId);
        return repository.runErrors(runId);
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
