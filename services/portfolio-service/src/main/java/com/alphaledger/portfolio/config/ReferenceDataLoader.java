package com.alphaledger.portfolio.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upserts the instrument master on startup with the IDs from the file, so instrument IDs are the same in every
 * service. Re-running is safe; editing a row in the file updates the instrument. Instrument status is kept.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "alphaledger.reference-data.instruments.enabled", havingValue = "true",
        matchIfMissing = true)
public class ReferenceDataLoader implements ApplicationRunner {

    static final String RESOURCE = "reference-data/instruments/instrument_master.csv";
    private static final Logger log = LoggerFactory.getLogger(ReferenceDataLoader.class);

    private final JdbcTemplate jdbc;

    public ReferenceDataLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        List<InstrumentMasterFile.Row> rows;
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            rows = InstrumentMasterFile.read(in);
        }
        for (InstrumentMasterFile.Row r : rows) {
            jdbc.update("""
                    INSERT INTO instrument (id, isin, symbol, exchange, name, instrument_type, asset_class,
                                            asset_class_proxy, currency, amfi_scheme_code, is_synthetic)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'INR', ?, ?)
                    ON CONFLICT (id) DO UPDATE SET
                        isin = EXCLUDED.isin, symbol = EXCLUDED.symbol, exchange = EXCLUDED.exchange,
                        name = EXCLUDED.name, instrument_type = EXCLUDED.instrument_type,
                        asset_class = EXCLUDED.asset_class, asset_class_proxy = EXCLUDED.asset_class_proxy,
                        amfi_scheme_code = EXCLUDED.amfi_scheme_code, is_synthetic = EXCLUDED.is_synthetic
                    """, r.instrumentId(), r.isin(), r.symbol(), r.exchange(), r.name(), r.instrumentType().name(),
                    r.assetClass().name(), r.assetClassProxy().name(), r.amfiSchemeCode(), r.synthetic());
        }
        log.info("Instrument master loaded: {} instruments", rows.size());
    }
}
