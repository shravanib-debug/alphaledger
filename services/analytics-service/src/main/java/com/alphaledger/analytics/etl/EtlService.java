package com.alphaledger.analytics.etl;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.error.ConflictException;
import com.alphaledger.common.error.NotFoundException;
import com.alphaledger.common.error.ValidationException;

/**
 * Starts ETL runs (nightly, or on request) and records each in {@code core.etl_run}. One run at a time; a run for a
 * date that already ran simply runs again (every step is idempotent).
 */
@Service
public class EtlService {

    private static final Logger log = LoggerFactory.getLogger(EtlService.class);

    private final JobOperator jobOperator;
    private final Job job;
    private final JdbcTemplate jdbc;
    private final MarketClock marketClock;
    private final Clock clock;

    public EtlService(JobOperator jobOperator, Job analyticsEtlJob, JdbcTemplate jdbc, MarketClock marketClock,
                      Clock clock) {
        this.jobOperator = jobOperator;
        this.job = analyticsEtlJob;
        this.jdbc = jdbc;
        this.marketClock = marketClock;
        this.clock = clock;
    }

    /** Nightly at 02:00 IST: loads the previous day, after the 23:45 end-of-day valuation. */
    @Scheduled(cron = "${alphaledger.analytics.etl-cron:0 0 2 * * *}", zone = "Asia/Kolkata")
    public void nightly() {
        try {
            run(marketClock.today().minusDays(1), false);
        } catch (RuntimeException ex) {
            log.warn("Nightly analytics ETL not run: {}", ex.getMessage());
        }
    }

    /** Runs the ETL synchronously and returns the run's {@code etl_run} row. */
    public synchronized Map<String, Object> run(LocalDate runDate, boolean fullRefresh) {
        if (runDate.isAfter(marketClock.today())) {
            throw new ValidationException("date: cannot be in the future");
        }
        Integer running = jdbc.queryForObject("SELECT count(*) FROM core.etl_run WHERE status = 'RUNNING' "
                + "AND started_at > now() - INTERVAL '2 hours'", Integer.class);
        if (running != null && running > 0) {
            throw new ConflictException("An analytics ETL run is already in progress");
        }
        long id = jdbc.queryForObject("""
                INSERT INTO core.etl_run (run_date, full_refresh, status, started_at) VALUES (?, ?, 'RUNNING', ?)
                RETURNING id
                """, Long.class, Date.valueOf(runDate), fullRefresh, Timestamp.from(clock.instant()));
        String status;
        String message = null;
        try {
            JobExecution execution = jobOperator.start(job, new JobParametersBuilder()
                    .addLocalDate(EtlJobConfig.RUN_DATE, runDate)
                    .addString(EtlJobConfig.FULL_REFRESH, String.valueOf(fullRefresh))
                    .addLong(EtlJobConfig.ETL_RUN_ID, id)
                    .toJobParameters());
            status = execution.getStatus() == BatchStatus.COMPLETED ? "COMPLETED" : "FAILED";
            if (!"COMPLETED".equals(status)) {
                List<Throwable> failures = execution.getAllFailureExceptions();
                message = failures.isEmpty() ? execution.getExitStatus().getExitDescription()
                        : failures.stream().map(Throwable::getMessage).collect(Collectors.joining("; "));
            }
        } catch (Exception ex) {
            status = "FAILED";
            message = ex.getMessage();
        }
        jdbc.update("UPDATE core.etl_run SET status = ?, message = ?, finished_at = ? WHERE id = ?", status,
                message == null ? null : message.substring(0, Math.min(message.length(), 1000)),
                Timestamp.from(clock.instant()), id);
        log.info("Analytics ETL run {} for {} ({}): {}", id, runDate, fullRefresh ? "full refresh" : "incremental",
                status);
        return run(id);
    }

    public Map<String, Object> run(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, run_date::TEXT AS run_date, full_refresh, status, rows_extracted::text AS rows_extracted, "
                + "rows_loaded::text AS rows_loaded, message, started_at, finished_at FROM core.etl_run WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new NotFoundException("ETL run " + id + " not found");
        }
        return rows.get(0);
    }
}
