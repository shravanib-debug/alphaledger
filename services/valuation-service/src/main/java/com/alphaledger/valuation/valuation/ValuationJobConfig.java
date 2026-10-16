package com.alphaledger.valuation.valuation;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
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

/**
 * End-of-day valuation (architecture.md §5.4) as a Spring Batch job: one run per valuation date, recorded with its
 * status in the batch tables. Scheduled on business days after the day's prices are in (default 20:00 IST).
 */
@Configuration
public class ValuationJobConfig {

    static final String DATE_PARAM = "valuationDate";
    private static final Logger log = LoggerFactory.getLogger(ValuationJobConfig.class);

    @Bean
    public Step valuationSnapshotStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                      ValuationService valuations) {
        return new StepBuilder("valuationSnapshotStep", jobRepository)
                .tasklet((contribution, context) -> {
                    LocalDate date = (LocalDate) context.getStepContext().getJobParameters().get(DATE_PARAM);
                    contribution.incrementWriteCount(valuations.runEndOfDay(date));
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Job valuationSnapshotJob(JobRepository jobRepository, Step valuationSnapshotStep) {
        return new JobBuilder("valuationSnapshotJob", jobRepository).start(valuationSnapshotStep).build();
    }

    @Bean
    public EndOfDayValuation endOfDayValuation(JobOperator jobOperator, Job valuationSnapshotJob,
                                               BusinessDayCalendar calendar, MarketClock clock) {
        return new EndOfDayValuation(jobOperator, valuationSnapshotJob, calendar, clock);
    }

    public static class EndOfDayValuation {

        private final JobOperator jobOperator;
        private final Job job;
        private final BusinessDayCalendar calendar;
        private final MarketClock clock;

        EndOfDayValuation(JobOperator jobOperator, Job job, BusinessDayCalendar calendar, MarketClock clock) {
            this.jobOperator = jobOperator;
            this.job = job;
            this.calendar = calendar;
            this.clock = clock;
        }

        @Scheduled(cron = "${alphaledger.valuation.eod-cron:0 0 20 * * MON-FRI}", zone = "Asia/Kolkata")
        public void scheduled() {
            LocalDate today = clock.today();
            if (calendar.isBusinessDay(today)) {
                run(today);
            }
        }

        /** Runs the job for a date and returns its status; a date that already completed is not run again. */
        public String run(LocalDate date) {
            try {
                JobExecution execution = jobOperator.start(job,
                        new JobParametersBuilder().addLocalDate(DATE_PARAM, date).toJobParameters());
                return execution.getStatus().name();
            } catch (Exception ex) {
                log.warn("End-of-day valuation for {} not started: {}", date, ex.getMessage());
                return "NOT_STARTED: " + ex.getMessage();
            }
        }
    }
}
