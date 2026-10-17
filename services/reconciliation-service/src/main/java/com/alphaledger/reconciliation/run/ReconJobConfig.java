package com.alphaledger.reconciliation.run;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.support.ListItemReader;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.alphaledger.reconciliation.repository.ReconRepository;

/**
 * Reconciliation as a Spring Batch job (architecture.md §5.5):
 * <ol>
 * <li>{@code stageCustodianRecords}: chunk step, reader (rows of the stored file) → processor (validation) → writer
 * (custodian_record / custodian_record_error), 500 rows per chunk;</li>
 * <li>{@code matchAndRaiseBreaks}: matching engine, breaks, events and run summary.</li>
 * </ol>
 * Job parameters: {@code runId} and {@code runDate}; each run is a separate, recorded job instance.
 */
@Configuration
public class ReconJobConfig {

    static final int CHUNK_SIZE = 500;

    @Bean
    @StepScope
    public ListItemReader<StagedRow.RawRow> custodianRowReader(@Value("#{jobParameters['runId']}") Long runId,
                                                               ReconRepository repository) {
        String content = new String(repository.fileContent(runId), StandardCharsets.UTF_8).replace("﻿", "");
        String[] lines = content.split("\\r?\\n");
        List<StagedRow.RawRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {   // line 1 is the header, checked before the run starts
            if (!lines[i].isBlank()) {
                rows.add(new StagedRow.RawRow(i + 1, lines[i]));
            }
        }
        return new ListItemReader<>(rows);
    }

    @Bean
    @StepScope
    public CustodianRowValidator custodianRowValidator(@Value("#{jobParameters['runId']}") Long runId,
                                                       @Value("#{jobParameters['runDate']}") LocalDate runDate) {
        return new CustodianRowValidator(runId, runDate);
    }

    @Bean
    public ItemWriter<StagedRow> stagingWriter(ReconRepository repository) {
        return chunk -> repository.insertStaged(chunk.getItems());
    }

    @Bean
    public Step stageCustodianRecords(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                      ListItemReader<StagedRow.RawRow> custodianRowReader,
                                      CustodianRowValidator custodianRowValidator, ItemWriter<StagedRow> stagingWriter) {
        return new StepBuilder("stageCustodianRecords", jobRepository)
                .<StagedRow.RawRow, StagedRow>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(custodianRowReader)
                .processor(custodianRowValidator)
                .writer(stagingWriter)
                .build();
    }

    @Bean
    public Step matchAndRaiseBreaks(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                    ReconMatchService matchService) {
        return new StepBuilder("matchAndRaiseBreaks", jobRepository)
                .tasklet((contribution, context) -> {
                    var params = context.getStepContext().getJobParameters();
                    matchService.matchAndRaiseBreaks((Long) params.get("runId"), (LocalDate) params.get("runDate"));
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Job reconciliationJob(JobRepository jobRepository, Step stageCustodianRecords, Step matchAndRaiseBreaks) {
        return new JobBuilder("reconciliationJob", jobRepository)
                .start(stageCustodianRecords)
                .next(matchAndRaiseBreaks)
                .build();
    }
}
