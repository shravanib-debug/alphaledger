package com.alphaledger.analytics.etl;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;

import com.alphaledger.analytics.dq.DataQualityChecker;
import com.alphaledger.analytics.dq.DataQualityChecker.CheckResult;

/**
 * The analytics ETL as a Spring Batch job ({@value #JOB}): extract -> load -> data quality. Each step records its
 * row counts on the run's {@code core.etl_run} row; the data-quality step fails the job when a CRITICAL check fails.
 * Parameters: {@code runDate}, {@code fullRefresh} and {@code etlRunId} (unique, so the same date can run again).
 */
@Configuration
public class EtlJobConfig {

    public static final String JOB = "analyticsEtlJob";
    static final String RUN_DATE = "runDate";
    static final String FULL_REFRESH = "fullRefresh";
    static final String ETL_RUN_ID = "etlRunId";
    private static final Logger log = LoggerFactory.getLogger(EtlJobConfig.class);

    @Bean
    public Step extractStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                            Extractor extractor, JdbcTemplate jdbc, JsonMapper jsonMapper) {
        return new StepBuilder("extractStep", jobRepository)
                .tasklet((contribution, context) -> {
                    JobParameters p = context.getStepContext().getStepExecution().getJobParameters();
                    Map<String, Integer> rows = extractor.extract(runDate(p), fullRefresh(p));
                    contribution.incrementWriteCount(rows.values().stream().mapToInt(Integer::intValue).sum());
                    jdbc.update("UPDATE core.etl_run SET rows_extracted = ?::jsonb WHERE id = ?",
                            jsonMapper.writeValueAsString(rows), etlRunId(p));
                    log.info("Analytics extract for {}: {}", runDate(p), rows);
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Step loadStep(JobRepository jobRepository, PlatformTransactionManager transactionManager, Loader loader,
                         JdbcTemplate jdbc, JsonMapper jsonMapper) {
        return new StepBuilder("loadStep", jobRepository)
                .tasklet((contribution, context) -> {
                    JobParameters p = context.getStepContext().getStepExecution().getJobParameters();
                    Map<String, Integer> rows = loader.load(runDate(p), fullRefresh(p));
                    contribution.incrementWriteCount(rows.values().stream().mapToInt(Integer::intValue).sum());
                    jdbc.update("UPDATE core.etl_run SET rows_loaded = ?::jsonb WHERE id = ?",
                            jsonMapper.writeValueAsString(rows), etlRunId(p));
                    log.info("Analytics load for {}: {}", runDate(p), rows);
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Step dataQualityStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                DataQualityChecker checker) {
        return new StepBuilder("dataQualityStep", jobRepository)
                .tasklet((contribution, context) -> {
                    JobParameters p = context.getStepContext().getStepExecution().getJobParameters();
                    List<CheckResult> results = checker.run(etlRunId(p), runDate(p), fullRefresh(p));
                    List<CheckResult> critical = results.stream()
                            .filter(r -> !r.passed() && "CRITICAL".equals(r.severity())).toList();
                    if (!critical.isEmpty()) {
                        throw new DataQualityException("Critical data-quality checks failed: " + critical.stream()
                                .map(r -> r.name() + " (expected " + r.expected() + ", got " + r.actual() + ")")
                                .collect(Collectors.joining(", ")));
                    }
                    log.info("Analytics data quality for {}: {} checks, {} warnings", runDate(p), results.size(),
                            results.stream().filter(r -> !r.passed()).count());
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Job analyticsEtlJob(JobRepository jobRepository, Step extractStep, Step loadStep, Step dataQualityStep) {
        return new JobBuilder(JOB, jobRepository).start(extractStep).next(loadStep).next(dataQualityStep).build();
    }

    private static LocalDate runDate(JobParameters p) {
        return p.getLocalDate(RUN_DATE);
    }

    private static boolean fullRefresh(JobParameters p) {
        return "true".equals(p.getString(FULL_REFRESH));
    }

    private static long etlRunId(JobParameters p) {
        return p.getLong(ETL_RUN_ID);
    }

    /** A CRITICAL data-quality failure; fails the job. */
    public static class DataQualityException extends RuntimeException {
        public DataQualityException(String message) {
            super(message);
        }
    }
}
