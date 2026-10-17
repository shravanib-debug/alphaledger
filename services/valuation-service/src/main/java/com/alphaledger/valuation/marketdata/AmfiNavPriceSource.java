package com.alphaledger.valuation.marketdata;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.alphaledger.valuation.marketdata.AmfiNavJobConfig.AmfiRun;
import com.alphaledger.valuation.marketdata.AmfiNavJobConfig.AmfiRuns;

/** Primary source for mutual funds: AMFI's daily NAVAll.txt, parsed by {@code amfiNavIngestJob}. */
@Component
public class AmfiNavPriceSource implements PriceSource {

    private final MarketDataFiles files;
    private final JobOperator jobOperator;
    private final Job job;
    private final AmfiRuns runs;

    public AmfiNavPriceSource(MarketDataFiles files, JobOperator jobOperator,
                              @Qualifier("amfiNavIngestJob") Job job, AmfiRuns runs) {
        this.files = files;
        this.jobOperator = jobOperator;
        this.job = job;
        this.runs = runs;
    }

    @Override
    public PriceSourceName name() {
        return PriceSourceName.AMFI_NAV;
    }

    @Override
    public boolean supports(InstrumentRef instrument) {
        return !instrument.synthetic() && "MUTUAL_FUND".equals(instrument.instrumentType());
    }

    @Override
    public PriceBatch fetch(LocalDate dataDate, Collection<InstrumentRef> instruments) {
        MarketDataFiles.RawFile file = files.amfiNav(dataDate).orElseThrow(() ->
                new PriceFileUnavailableException("No AMFI NAVAll.txt for " + dataDate));
        Set<String> wanted = instruments.stream().map(InstrumentRef::isin).collect(Collectors.toSet());
        String key = UUID.randomUUID().toString();
        AmfiRun run = new AmfiRun(file.content(), wanted);
        runs.put(key, run);
        try {
            JobExecution execution = jobOperator.start(job,
                    new JobParametersBuilder().addString(AmfiNavJobConfig.RUN_KEY, key)
                            .addLocalDate("dataDate", dataDate).toJobParameters());
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                Throwable cause = execution.getAllFailureExceptions().stream().findFirst().orElse(null);
                throw new IllegalStateException("amfiNavIngestJob " + execution.getStatus()
                        + (cause == null ? "" : ": " + cause.getMessage()), cause);
            }
            return new PriceBatch(PriceSourceName.AMFI_NAV, dataDate, file.name(), MarketTime.sha256(file.content()),
                    new ArrayList<>(run.records), new ArrayList<>(run.rejected), false, false);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("amfiNavIngestJob could not start: " + ex.getMessage(), ex);
        } finally {
            runs.remove(key);
        }
    }
}
