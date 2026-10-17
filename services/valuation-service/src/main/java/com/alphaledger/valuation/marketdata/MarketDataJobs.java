package com.alphaledger.valuation.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.alphaledger.common.calendar.BusinessDayCalendar;
import com.alphaledger.common.calendar.MarketClock;

/** Scheduled market-data work, in the market time zone. */
@Component
public class MarketDataJobs {

    private static final Logger log = LoggerFactory.getLogger(MarketDataJobs.class);

    private final PriceIngestionService ingestion;
    private final MarketDataRepository repository;
    private final PriceQueryService prices;
    private final SyntheticPriceSource synthetic;
    private final BusinessDayCalendar calendar;
    private final MarketClock clock;
    private final MarketDataProperties properties;

    public MarketDataJobs(PriceIngestionService ingestion, MarketDataRepository repository, PriceQueryService prices,
                          SyntheticPriceSource synthetic, BusinessDayCalendar calendar, MarketClock clock,
                          MarketDataProperties properties) {
        this.ingestion = ingestion;
        this.repository = repository;
        this.prices = prices;
        this.synthetic = synthetic;
        this.calendar = calendar;
        this.clock = clock;
        this.properties = properties;
    }

    /** End-of-day run on business days, after files are published (default 23:30 IST: AMFI NAVs are due by about 23:00). */
    @Scheduled(cron = "${alphaledger.marketdata.daily-cron:0 30 23 * * MON-FRI}", zone = "Asia/Kolkata")
    public void daily() {
        LocalDate today = clock.today();
        if (calendar.isBusinessDay(today)) {
            ingestion.runDaily(today).forEach(s -> log.info("Daily price run: {}", s));
        }
    }

    /** OFFLINE demo only: a synthetic tick for each real instrument every minute during market hours. */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    public void intradayTicks() {
        if (!properties.intradayTicksEnabled() || properties.mode() != MarketDataProperties.Mode.OFFLINE) {
            return;
        }
        LocalDate today = clock.today();
        LocalTime time = LocalTime.ofInstant(clock.now(), clock.zone());
        if (!calendar.isBusinessDay(today) || time.isBefore(LocalTime.of(9, 15)) || time.isAfter(MarketTime.MARKET_CLOSE)) {
            return;
        }
        // Real instruments only: synthetic instruments keep their quarterly marks.
        Map<InstrumentRef, BigDecimal> latest = new LinkedHashMap<>();
        for (InstrumentRef instrument : repository.instruments()) {
            BigDecimal price = instrument.synthetic() ? null : prices.latest(instrument, today).price();
            if (price != null) {
                latest.put(instrument, price);
            }
        }
        ingestion.ingest(synthetic.intradayTicks(latest, today, clock.now()));
    }
}
