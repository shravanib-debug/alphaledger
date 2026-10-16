package com.alphaledger.position.batch;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import com.alphaledger.common.calendar.BusinessDayCalendar;
import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.position.repository.PositionRepository;

/**
 * Daily position snapshot (FR-POS-6) as a Spring Batch job, so every run is recorded with its parameters and status
 * in the batch tables. Runs on business days after market close; one run per snapshot date.
 */
@Configuration
public class PositionSnapshotJobConfig {

    static final String JOB = "positionSnapshotJob";
    static final String DATE_PARAM = "snapshotDate";
    private static final Logger log = LoggerFactory.getLogger(PositionSnapshotJobConfig.class);

    @Bean
    public Step positionSnapshotStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                     PositionRepository positions) {
        return new StepBuilder("positionSnapshotStep", jobRepository)
                .tasklet((contribution, context) -> {
                    LocalDate date = (LocalDate) context.getStepContext().getJobParameters().get(DATE_PARAM);
                    int rows = positions.snapshot(date);
                    contribution.incrementWriteCount(rows);
                    log.info("Position snapshot for {}: {} positions", date, rows);
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Job positionSnapshotJob(JobRepository jobRepository, Step positionSnapshotStep) {
        return new JobBuilder(JOB, jobRepository).start(positionSnapshotStep).build();
    }

    @Bean
    public PositionSnapshotScheduler positionSnapshotScheduler(JobOperator jobOperator, Job positionSnapshotJob,
                                                               BusinessDayCalendar calendar, MarketClock clock) {
        return new PositionSnapshotScheduler(jobOperator, positionSnapshotJob, calendar, clock);
    }

    public static class PositionSnapshotScheduler {

        private final JobOperator jobOperator;
        private final Job job;
        private final BusinessDayCalendar calendar;
        private final MarketClock clock;

        PositionSnapshotScheduler(JobOperator jobOperator, Job job, BusinessDayCalendar calendar, MarketClock clock) {
            this.jobOperator = jobOperator;
            this.job = job;
            this.calendar = calendar;
            this.clock = clock;
        }

        @Scheduled(cron = "${alphaledger.position.snapshot-cron:0 45 18 * * MON-FRI}", zone = "Asia/Kolkata")
        public void scheduled() {
            LocalDate today = clock.today();
            if (calendar.isBusinessDay(today)) {
                run(today);
            }
        }

        /** Runs the snapshot for a date; a date that already completed is not run again. */
        public void run(LocalDate date) {
            try {
                jobOperator.start(job, new JobParametersBuilder().addLocalDate(DATE_PARAM, date).toJobParameters());
            } catch (Exception ex) {
                log.warn("Position snapshot for {} not started: {}", date, ex.getMessage());
            }
        }
    }
}
