package com.alphaledger.valuation.marketdata;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.instrument.InstrumentMaster;

/**
 * On startup: loads the instrument master into {@code instrument_ref} (same IDs as portfolio-service), then, when
 * seeding is enabled, the committed real seed prices ({@code reference-data/sample/prices/*.csv}) and the synthetic
 * quarterly marks; in OFFLINE mode it also runs the real bhavcopy and NAVAll parsers over the committed sample files. Every step is idempotent: re-running loads nothing new (checksums in the ingestion log).
 */
@Component
public class MarketDataStartup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketDataStartup.class);
    static final String SEED_PATTERN = "classpath*:reference-data/sample/prices/*.csv";

    private final MarketDataRepository repository;
    private final PriceIngestionService ingestion;
    private final SyntheticPriceSource synthetic;
    private final NseBhavcopyPriceSource bhavcopy;
    private final AmfiNavPriceSource amfi;
    private final MarketDataFiles files;
    private final MarketDataProperties properties;
    private final MarketClock clock;

    public MarketDataStartup(MarketDataRepository repository, PriceIngestionService ingestion,
                             SyntheticPriceSource synthetic, NseBhavcopyPriceSource bhavcopy,
                             AmfiNavPriceSource amfi, MarketDataFiles files, MarketDataProperties properties,
                             MarketClock clock) {
        this.repository = repository;
        this.ingestion = ingestion;
        this.synthetic = synthetic;
        this.bhavcopy = bhavcopy;
        this.amfi = amfi;
        this.files = files;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        int instruments = 0;
        for (InstrumentMaster.Row r : InstrumentMaster.readCommitted()) {
            repository.upsertInstrument(r.instrumentId(), r.isin(), r.symbol(), r.exchange(), r.name(),
                    r.instrumentType(), r.assetClass(), r.assetClassProxy(), r.amfiSchemeCode(), r.synthetic());
            instruments++;
        }
        log.info("Instrument reference loaded: {} instruments; market data mode {}, network {}", instruments,
                properties.mode(), properties.networkEnabled() ? "enabled" : "disabled");
        if (!properties.seedEnabled()) {
            return;
        }
        for (Resource seed : new PathMatchingResourcePatternResolver().getResources(SEED_PATTERN)) {
            try (InputStream in = seed.getInputStream()) {
                ingestion.ingest(CsvPriceFile.parse(seed.getFilename(), in.readAllBytes()));
            }
        }
        ingestion.runSource(synthetic, clock.today());
        if (properties.sampleEnabled() && properties.mode() == MarketDataProperties.Mode.OFFLINE) {
            loadOfflineSample();
        }
    }

    /** OFFLINE: the real parsers over every committed sample file (re-runs are SKIPPED_DUPLICATE by checksum). */
    void loadOfflineSample() {
        LocalDate today = clock.today();
        files.nseSampleDates().stream().filter(d -> !d.isAfter(today))
                .forEach(date -> log.info("Offline sample: {}", ingestion.runSource(bhavcopy, date)));
        files.amfiSampleDates().stream().filter(d -> !d.isAfter(today))
                .forEach(date -> log.info("Offline sample: {}", ingestion.runSource(amfi, date)));
    }
}
