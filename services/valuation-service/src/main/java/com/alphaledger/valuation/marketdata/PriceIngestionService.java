package com.alphaledger.valuation.marketdata;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.alphaledger.common.calendar.BusinessDayCalendar;
import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.event.EventTypes;
import com.alphaledger.common.event.PriceEvent;
import com.alphaledger.common.event.Topics;
import com.alphaledger.common.outbox.OutboxWriter;
import com.alphaledger.valuation.marketdata.PriceBatch.PriceRecord;
import com.alphaledger.valuation.marketdata.PriceBatch.RejectedRow;

/**
 * Price ingestion pipeline (architecture.md §5.3): checksum check → validate → dedupe/upsert → log.
 * Every loaded price is also published as {@code price.updated} (through the outbox, keyed by instrument).
 */
@Service
public class PriceIngestionService {

    private static final Logger log = LoggerFactory.getLogger(PriceIngestionService.class);

    private final MarketDataRepository repository;
    private final PriceSourceRegistry registry;
    private final BusinessDayCalendar calendar;
    private final MarketClock clock;
    private final boolean offline;
    private final OutboxWriter outbox;
    private final TransactionTemplate transaction;

    public PriceIngestionService(MarketDataRepository repository, PriceSourceRegistry registry,
                                 BusinessDayCalendar calendar, MarketClock clock, MarketDataProperties properties,
                                 OutboxWriter outbox, PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.repository = repository;
        this.registry = registry;
        this.calendar = calendar;
        this.clock = clock;
        this.offline = properties.mode() == MarketDataProperties.Mode.OFFLINE;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public record IngestionSummary(long ingestionId, PriceSourceName source, String status, int rowsRead,
                                   int rowsLoaded, int rowsRejected) {
    }

    /**
     * Loads one batch. A batch whose checksum was already loaded for the same source is logged as SKIPPED_DUPLICATE and
     * loads nothing. If loading fails part-way, the prices are rolled back and the attempt is logged as FAILED.
     */
    public IngestionSummary ingest(PriceBatch batch) {
        try {
            return transaction.execute(status -> ingestInTransaction(batch));
        } catch (RuntimeException ex) {
            log.error("Price ingestion from {} ({}) failed", batch.logSource(), batch.fileName(), ex);
            Long id = transaction.execute(status -> {
                long logId = repository.insertLog(batch, "FAILED", clock.now());
                repository.completeLog(logId, "FAILED", 0, 0, 0, truncate(ex.getMessage()), clock.now());
                return logId;
            });
            return new IngestionSummary(id == null ? -1 : id, batch.logSource(), "FAILED", 0, 0, 0);
        }
    }

    /**
     * Daily run for {@code dataDate}: for each instrument, tries the sources of its type in fallback order; a later
     * source only prices instruments the earlier ones did not.
     */
    public List<IngestionSummary> runDaily(LocalDate dataDate) {
        List<IngestionSummary> summaries = new ArrayList<>();
        List<InstrumentRef> instruments = repository.instruments();
        for (PriceSource source : registry.sourcesInOrder()) {
            List<InstrumentRef> todo = instruments.stream()
                    .filter(i -> registry.sourcesFor(i.instrumentType()).contains(source) && source.supports(i))
                    .filter(i -> !alreadyPricedByEarlierSource(i, source, dataDate))
                    .toList();
            if (!todo.isEmpty()) {
                summaries.add(ingest(source.fetch(dataDate, todo)));
            }
        }
        return summaries;
    }

    /** Runs a single source for its supported instruments (manual trigger). */
    public IngestionSummary runSource(PriceSource source, LocalDate dataDate) {
        List<InstrumentRef> supported = repository.instruments().stream().filter(source::supports).toList();
        return ingest(source.fetch(dataDate, supported));
    }

    private boolean alreadyPricedByEarlierSource(InstrumentRef instrument, PriceSource source, LocalDate dataDate) {
        List<PriceSourceName> earlier = new ArrayList<>();
        for (PriceSource s : registry.sourcesFor(instrument.instrumentType())) {
            if (s == source) {
                break;
            }
            earlier.add(s.name());
        }
        return repository.pricedOn(dataDate, earlier).contains(instrument.instrumentId());
    }

    private IngestionSummary ingestInTransaction(PriceBatch batch) {
        Instant started = clock.now();
        int read = batch.records().size() + batch.rejected().size();
        if (repository.alreadyLoaded(batch.logSource(), batch.checksum())) {
            long id = repository.insertLog(batch, "SKIPPED_DUPLICATE", started);
            repository.completeLog(id, "SKIPPED_DUPLICATE", read, 0, 0, "Same file already loaded", clock.now());
            return new IngestionSummary(id, batch.logSource(), "SKIPPED_DUPLICATE", read, 0, 0);
        }
        long id = repository.insertLog(batch, "RUNNING", started);
        Map<String, InstrumentRef> byIsin = repository.instrumentsByIsin();
        List<RejectedRow> rejected = new ArrayList<>(batch.rejected());
        int loaded = 0;
        for (PriceRecord record : batch.records()) {
            InstrumentRef instrument = byIsin.get(record.isin());
            if (instrument == null && !batch.rejectUnknownIsins()) {
                continue;   // market-wide files cover thousands of securities we do not hold
            }
            String reason = rejectionReason(record, instrument, batch.intradayTicks());
            if (reason != null) {
                rejected.add(new RejectedRow(record.rowNumber(), record.isin(), reason, null));
                continue;
            }
            repository.upsertPrice(record, instrument, id);
            outbox.append("Price", String.valueOf(instrument.instrumentId()), EventTypes.PRICE_UPDATED,
                    Topics.PRICE_UPDATED, String.valueOf(instrument.instrumentId()),
                    new PriceEvent(instrument.instrumentId(), instrument.isin(), record.price(), "INR",
                            record.dataDate(), record.asOf(), record.source().name()));
            loaded++;
        }
        repository.insertErrors(id, rejected);
        String status = rejected.isEmpty() ? "SUCCESS" : loaded > 0 ? "PARTIAL" : "FAILED";
        repository.completeLog(id, status, read, loaded, rejected.size(), null, clock.now());
        log.info("Price ingestion {} from {} ({}): {} loaded, {} rejected", status, batch.logSource(),
                batch.fileName(), loaded, rejected.size());
        return new IngestionSummary(id, batch.logSource(), status, read, loaded, rejected.size());
    }

    String rejectionReason(PriceRecord record, InstrumentRef instrument, boolean intradayTicks) {
        if (instrument == null) {
            return "Unknown ISIN " + record.isin();
        }
        if (record.price() == null || record.price().signum() <= 0) {
            return "Price must be greater than zero";
        }
        if (record.dataDate().isAfter(clock.today())) {
            return "data_date " + record.dataDate() + " is in the future";
        }
        boolean synthetic = record.source() == PriceSourceName.SYNTHETIC;
        if (synthetic && !instrument.synthetic() && !(intradayTicks && offline)) {
            return intradayTicks ? "Synthetic intraday ticks are only allowed in OFFLINE mode"
                    : "SYNTHETIC prices are only allowed for synthetic instruments";
        }
        if (!synthetic && instrument.synthetic()) {
            return "Synthetic instruments are priced only by SYNTHETIC marks";
        }
        if (!synthetic && !calendar.isBusinessDay(record.dataDate())) {
            return "data_date " + record.dataDate() + " is not a business day";
        }
        return null;
    }

    private static String truncate(String message) {
        return message == null || message.length() <= 500 ? message : message.substring(0, 500);
    }
}
