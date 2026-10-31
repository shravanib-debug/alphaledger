package com.alphaledger.reconciliation.run;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.error.ConflictException;
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

    private static final Logger log = LoggerFactory.getLogger(ReconRunService.class);

    private final ReconRepository repository;
    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Job reconciliationJob;
    private final MarketClock marketClock;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final Duration staleAfter;

    public ReconRunService(ReconRepository repository, JobOperator jobOperator, JobRepository jobRepository,
                           Job reconciliationJob, MarketClock marketClock, Clock clock,
                           PlatformTransactionManager transactionManager,
                           @Value("${alphaledger.recon.batch.stale-after:PT30M}") Duration staleAfter) {
        this.repository = repository;
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.staleAfter = staleAfter;
        this.reconciliationJob = reconciliationJob;
        this.marketClock = marketClock;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public record RunOutcome(Map<String, Object> run, boolean duplicate) {
    }

    public RunOutcome start(InputStreamSource file, long size, String fileName, LocalDate runDate, String source,
                            Long userId) throws IOException {
        if (runDate.isAfter(marketClock.today())) {
            throw new ValidationException("runDate: cannot be in the future");
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || !CustodianRowValidator.HEADER.equals(header.replace("﻿", "").strip())) {
                throw new ValidationException("file: first line must be '" + CustodianRowValidator.HEADER + "'");
            }
        }
        String checksum;
        try (InputStream in = file.getInputStream()) {
            checksum = sha256(in);
        }
        var existing = repository.findRun(runDate, source, checksum);
        if (existing.isPresent()) {
            long id = existing.get();
            // A failed run must not block its own file for ever: uploading it again resumes the run.
            if (repository.reopenRun(id, clock.instant())) {
                execute(id, runDate);
            }
            return new RunOutcome(summary(id), true);
        }
        Long runId = transaction.execute(status -> {
            long id = repository.insertRun(runDate, source, fileName, checksum, userId, clock.instant());
            try (InputStream in = file.getInputStream()) {
                repository.insertFile(id, in, size);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            return id;
        });
        execute(runId, runDate);
        return new RunOutcome(summary(runId), false);
    }

    /** Resumes a FAILED run from its last committed work (the same job instance). */
    public Map<String, Object> restart(long runId) {
        Map<String, Object> run = summary(runId);
        if (!repository.reopenRun(runId, clock.instant())) {
            throw new ConflictException("Run " + runId + " is " + run.get("status") + "; only a FAILED run can restart");
        }
        execute(runId, ((java.sql.Date) run.get("run_date")).toLocalDate());
        return summary(runId);
    }

    /**
     * Fails runs left RUNNING by a crashed instance, so they can be restarted. The age limit must exceed the longest
     * real run: with several instances, a younger run may be alive on another one.
     */
    @Scheduled(fixedDelayString = "${alphaledger.recon.batch.recovery-interval-ms:300000}")
    public void recoverStaleRuns() {
        for (long runId : repository.runningStartedBefore(clock.instant().minus(staleAfter))) {
            Map<String, Object> run = summary(runId);
            JobExecution last = jobRepository.getLastJobExecution(ReconJobConfig.JOB_NAME,
                    parameters(runId, ((java.sql.Date) run.get("run_date")).toLocalDate()));
            if (last != null && last.isRunning()) {
                jobOperator.recover(last);
            }
            repository.failRun(runId, "Interrupted: the service stopped during this run", clock.instant());
            log.warn("Reconciliation run {} was left running and is now FAILED", runId);
        }
    }

    private void execute(long runId, LocalDate runDate) {
        try {
            // With the same parameters, a failed job instance resumes instead of starting over.
            JobExecution execution = jobOperator.start(reconciliationJob, parameters(runId, runDate));
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                String message = execution.getAllFailureExceptions().stream().map(Throwable::getMessage)
                        .findFirst().orElse(execution.getStatus().name());
                repository.failRun(runId, message, clock.instant());
            }
        } catch (Exception ex) {
            repository.failRun(runId, ex.getMessage(), clock.instant());
        }
    }

    private static JobParameters parameters(long runId, LocalDate runDate) {
        return new JobParametersBuilder().addLong("runId", runId).addLocalDate("runDate", runDate).toJobParameters();
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

    static String sha256(InputStream content) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream in = new DigestInputStream(content, digest)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
