package com.alphaledger.valuation.marketdata;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.support.ListItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.alphaledger.valuation.marketdata.PriceBatch.PriceRecord;
import com.alphaledger.valuation.marketdata.PriceBatch.RejectedRow;

/**
 * {@code amfiNavIngestJob}: reader (lines of NAVAll.txt) → processor ({@link AmfiNavParser}: skip headings and funds
 * we do not hold, reject bad NAVs) → writer (collects the run's prices). The collected batch then goes through the
 * normal ingestion pipeline (dedupe, validate, load, log, publish), like every other source.
 */
@Configuration
public class AmfiNavJobConfig {

    static final String RUN_KEY = "runKey";

    /** The input and output of one job run, keyed by the {@value #RUN_KEY} job parameter. */
    public static class AmfiRun {
        final byte[] content;
        final Set<String> wantedIsins;
        final List<PriceRecord> records = Collections.synchronizedList(new ArrayList<>());
        final List<RejectedRow> rejected = Collections.synchronizedList(new ArrayList<>());

        AmfiRun(byte[] content, Set<String> wantedIsins) {
            this.content = content;
            this.wantedIsins = wantedIsins;
        }
    }

    /** Runs in progress (a job reads its input from here and writes its output back). */
    public static class AmfiRuns {
        private final Map<String, AmfiRun> runs = new ConcurrentHashMap<>();

        void put(String key, AmfiRun run) {
            runs.put(key, run);
        }

        AmfiRun get(String key) {
            AmfiRun run = runs.get(key);
            if (run == null) {
                throw new IllegalStateException("No AMFI run " + key);
            }
            return run;
        }

        void remove(String key) {
            runs.remove(key);
        }
    }

    public record NumberedLine(int rowNumber, String line) {
    }

    @Bean
    public AmfiRuns amfiRuns() {
        return new AmfiRuns();
    }

    @Bean
    @StepScope
    public ListItemReader<NumberedLine> amfiNavReader(AmfiRuns runs, @Value("#{jobParameters['runKey']}") String key) {
        String[] lines = new String(runs.get(key).content, StandardCharsets.UTF_8).split("\r?\n");
        AmfiNavParser.checkHeader(lines.length == 0 ? null : lines[0]);
        List<NumberedLine> items = new ArrayList<>(lines.length);
        for (int i = 1; i < lines.length; i++) {
            items.add(new NumberedLine(i + 1, lines[i]));
        }
        return new ListItemReader<>(items);
    }

    @Bean
    @StepScope
    public ItemProcessor<NumberedLine, AmfiNavParser.Line> amfiNavProcessor(
            AmfiRuns runs, @Value("#{jobParameters['runKey']}") String key) {
        Set<String> wanted = runs.get(key).wantedIsins;
        return item -> {
            AmfiNavParser.Line line = AmfiNavParser.parse(item.rowNumber(), item.line(), wanted);
            return line instanceof AmfiNavParser.Skipped ? null : line;   // null filters the item out
        };
    }

    @Bean
    @StepScope
    public ItemWriter<AmfiNavParser.Line> amfiNavWriter(AmfiRuns runs, @Value("#{jobParameters['runKey']}") String key) {
        AmfiRun run = runs.get(key);
        return chunk -> chunk.forEach(line -> {
            switch (line) {
                case AmfiNavParser.Price p -> run.records.add(p.record());
                case AmfiNavParser.Rejected r -> run.rejected.add(r.row());
                case AmfiNavParser.Skipped s -> { }
            }
        });
    }

    @Bean
    public Step amfiNavParseStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                 ListItemReader<NumberedLine> amfiNavReader,
                                 ItemProcessor<NumberedLine, AmfiNavParser.Line> amfiNavProcessor,
                                 ItemWriter<AmfiNavParser.Line> amfiNavWriter) {
        return new StepBuilder("amfiNavParseStep", jobRepository)
                .<NumberedLine, AmfiNavParser.Line>chunk(1000)
                .transactionManager(transactionManager)
                .reader(amfiNavReader)
                .processor(amfiNavProcessor)
                .writer(amfiNavWriter)
                .build();
    }

    @Bean
    public Job amfiNavIngestJob(JobRepository jobRepository, Step amfiNavParseStep) {
        return new JobBuilder("amfiNavIngestJob", jobRepository).start(amfiNavParseStep).build();
    }
}
