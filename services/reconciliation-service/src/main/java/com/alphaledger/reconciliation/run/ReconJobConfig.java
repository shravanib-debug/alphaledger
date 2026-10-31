package com.alphaledger.reconciliation.run;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.separator.SimpleRecordSeparatorPolicy;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.alphaledger.reconciliation.repository.ReconRepository;

/**
 * Reconciliation as a restartable Spring Batch job (architecture.md §5.5):
 * <ol>
 * <li>{@code loadRawRows}: streams the stored file into {@code custodian_raw_row};</li>
 * <li>{@code stageCustodianRecords}: row-range partitions, each a chunk step that validates and writes
 * custodian_record / custodian_record_error, with retry of transient database failures and skip of rows the database
 * refuses;</li>
 * <li>{@code flagDuplicates}: rejects repeated match keys, keeping the earliest row;</li>
 * <li>{@code matchAndRaiseBreaks}: matching engine, breaks, events and run summary.</li>
 * </ol>
 * Job parameters: {@code runId} and {@code runDate}; each run is one job instance, so starting a failed run again with
 * the same parameters resumes it from the last committed chunk of each unfinished partition.
 */
@Configuration
public class ReconJobConfig {

    static final int CHUNK_SIZE = 500;
    static final String JOB_NAME = "reconciliationJob";

    @Bean
    @StepScope
    public FlatFileItemReader<StagedRow.RawRow> custodianFileReader(@Value("#{jobParameters['runId']}") Long runId,
                                                                    ReconRepository repository) {
        // The mapper's line number is the real file line, blank lines included, even after a restart.
        FlatFileItemReader<StagedRow.RawRow> reader = new FlatFileItemReader<>(new StoredFileResource(repository, runId),
                (line, lineNumber) -> new StagedRow.RawRow(lineNumber, line.replace('\0', '�')));
        reader.setName("custodianFileReader");
        reader.setEncoding(StandardCharsets.UTF_8.name());
        reader.setLinesToSkip(1);                  // the header, checked before the run starts
        reader.setComments(new String[0]);         // the default would silently drop rows starting with '#'
        reader.setRecordSeparatorPolicy(new SimpleRecordSeparatorPolicy());
        return reader;
    }

    @Bean
    public Step loadRawRows(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                            FlatFileItemReader<StagedRow.RawRow> custodianFileReader, ReconRepository repository,
                            @Value("${alphaledger.recon.batch.chunk-size:" + CHUNK_SIZE + "}") int chunkSize) {
        ItemProcessor<StagedRow.RawRow, StagedRow.RawRow> dropBlankLines = row -> row.line().isBlank() ? null : row;
        return new StepBuilder("loadRawRows", jobRepository)
                .<StagedRow.RawRow, StagedRow.RawRow>chunk(chunkSize)
                .transactionManager(transactionManager)
                .reader(custodianFileReader)
                .processor(dropBlankLines)
                .writer(chunk -> repository.insertRawRows(custodianRunId(), chunk.getItems()))
                .build();
    }

    private static long custodianRunId() {
        return (Long) org.springframework.batch.core.scope.context.StepSynchronizationManager.getContext()
                .getJobParameters().get("runId");
    }

    /** Equal row-number ranges: deterministic for a stored file, so a restart finds the same partitions. */
    @Bean
    @StepScope
    public Partitioner rowRangePartitioner(@Value("#{jobParameters['runId']}") Long runId, ReconRepository repository) {
        return gridSize -> {
            int[] bounds = repository.rawRowBounds(runId);
            Map<String, ExecutionContext> partitions = new HashMap<>();
            long span = (long) bounds[1] - bounds[0] + 1;
            int count = (int) Math.max(1, Math.min(gridSize, span));
            for (int i = 0; i < count; i++) {
                ExecutionContext context = new ExecutionContext();
                context.putInt("minRow", (int) (bounds[0] + span * i / count));
                context.putInt("maxRow", (int) (bounds[0] + span * (i + 1) / count - 1));
                partitions.put("rows" + i, context);
            }
            return partitions;
        };
    }

    @Bean
    @StepScope
    public JdbcPagingItemReader<StagedRow.RawRow> rawRowReader(@Value("#{jobParameters['runId']}") Long runId,
                                                               @Value("#{stepExecutionContext['minRow']}") Integer minRow,
                                                               @Value("#{stepExecutionContext['maxRow']}") Integer maxRow,
                                                               DataSource dataSource,
                                                               @Value("${alphaledger.recon.batch.chunk-size:" + CHUNK_SIZE + "}") int chunkSize)
            throws Exception {
        return new JdbcPagingItemReaderBuilder<StagedRow.RawRow>()
                .name("rawRowReader")
                .dataSource(dataSource)
                .selectClause("SELECT row_number, line")
                .fromClause("FROM custodian_raw_row")
                .whereClause("WHERE run_id = :runId AND row_number BETWEEN :minRow AND :maxRow")
                .sortKeys(Map.of("row_number", Order.ASCENDING))
                .parameterValues(Map.of("runId", runId, "minRow", minRow, "maxRow", maxRow))
                .pageSize(chunkSize)
                .rowMapper((rs, i) -> new StagedRow.RawRow(rs.getInt("row_number"), rs.getString("line")))
                .build();
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

    /** A skipped row is recorded in its own transaction: the chunk's may be rolling back. */
    @Bean
    public SkipListener<StagedRow.RawRow, StagedRow> stagingSkipListener(ReconRepository repository,
                                                                         PlatformTransactionManager transactionManager) {
        TransactionTemplate ownTransaction = new TransactionTemplate(transactionManager);
        ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new SkipListener<>() {
            @Override
            public void onSkipInWrite(StagedRow row, Throwable cause) {
                ownTransaction.executeWithoutResult(status -> repository.insertStaged(List.of(StagedRow.rejected(
                        row.runId(), row.rowNumber(),
                        "row refused by the database: " + NestedExceptionUtils.getMostSpecificCause(cause).getMessage(),
                        row.rawRow()))));
            }
        };
    }

    @Bean
    public Step stageCustodianPartition(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                        JdbcPagingItemReader<StagedRow.RawRow> rawRowReader,
                                        CustodianRowValidator custodianRowValidator, ItemWriter<StagedRow> stagingWriter,
                                        SkipListener<StagedRow.RawRow, StagedRow> stagingSkipListener,
                                        @Value("${alphaledger.recon.batch.chunk-size:" + CHUNK_SIZE + "}") int chunkSize,
                                        @Value("${alphaledger.recon.batch.retry-limit:3}") long retryLimit,
                                        @Value("${alphaledger.recon.batch.skip-limit:100}") long skipLimit) {
        return new StepBuilder("stageCustodianPartition", jobRepository)
                .<StagedRow.RawRow, StagedRow>chunk(chunkSize)
                .transactionManager(transactionManager)
                .reader(rawRowReader)
                .processor(custodianRowValidator)
                .writer(stagingWriter)
                .faultTolerant()
                .retryPolicy(RetryPolicy.builder().includes(TransientDataAccessException.class).maxRetries(retryLimit)
                        .delay(Duration.ofMillis(100)).multiplier(2).build())
                .skip(DataIntegrityViolationException.class)
                .skipLimit(skipLimit)                       // per partition
                .skipListener(stagingSkipListener)
                .build();
    }

    /** Concurrency is bounded by the grid size; keep it well below the connection pool size. */
    @Bean
    public Step stageCustodianRecords(JobRepository jobRepository, Step stageCustodianPartition,
                                      Partitioner rowRangePartitioner,
                                      @Value("${alphaledger.recon.batch.grid-size:4}") int gridSize) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("recon-stage-");
        executor.setConcurrencyLimit(gridSize);
        return new StepBuilder("stageCustodianRecords", jobRepository)
                .partitioner("stageCustodianPartition", rowRangePartitioner)
                .step(stageCustodianPartition)
                .gridSize(gridSize)
                .taskExecutor(executor)
                .build();
    }

    /** Set-based, so it sees every partition and gives the same answer on a re-run. */
    @Bean
    public Step flagDuplicates(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                               ReconRepository repository) {
        return new StepBuilder("flagDuplicates", jobRepository)
                .tasklet((contribution, context) -> {
                    repository.moveDuplicatesToErrors((Long) context.getStepContext().getJobParameters().get("runId"));
                    return RepeatStatus.FINISHED;
                }, transactionManager)
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
    public Job reconciliationJob(JobRepository jobRepository, Step loadRawRows, Step stageCustodianRecords,
                                 Step flagDuplicates, Step matchAndRaiseBreaks) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(loadRawRows)
                .next(stageCustodianRecords)
                .next(flagDuplicates)
                .next(matchAndRaiseBreaks)
                .build();
    }
}
