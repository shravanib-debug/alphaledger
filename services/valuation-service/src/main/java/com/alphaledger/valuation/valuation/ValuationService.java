package com.alphaledger.valuation.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.error.ValidationException;
import com.alphaledger.common.event.EventEnvelope;
import com.alphaledger.common.event.EventTypes;
import com.alphaledger.common.event.LimitBreachEvent;
import com.alphaledger.common.event.PositionEvent;
import com.alphaledger.common.event.ProcessedEvents;
import com.alphaledger.common.event.Topics;
import com.alphaledger.common.event.ValuationEvent;
import com.alphaledger.common.outbox.OutboxWriter;
import com.alphaledger.valuation.marketdata.MarketDataRepository;
import com.alphaledger.valuation.marketdata.PriceQueryService;
import com.alphaledger.valuation.marketdata.PriceQueryService.LatestPrice;
import com.alphaledger.valuation.nav.LedgerRepository;
import com.alphaledger.valuation.valuation.NavCalculator.Nav;
import com.alphaledger.valuation.valuation.ValuationCalculator.Exposure;
import com.alphaledger.valuation.valuation.ValuationCalculator.Holding;
import com.alphaledger.valuation.valuation.ValuationCalculator.PortfolioValuation;
import com.alphaledger.valuation.valuation.ValuationCalculator.Quote;

/**
 * Portfolio Market Value, P&L, exposure (FR-VAL-2 to FR-VAL-7) and full NAV = market value + cash - liabilities
 * (FR-VAL-10). Exposure and limits stay shares of market value. Live valuations are computed on request from the
 * latest positions and prices; the end-of-day run stores an immutable snapshot per portfolio, checks limits and
 * publishes {@code valuation.calculated} (and {@code limit.breached}).
 */
@Service
public class ValuationService {

    public static final String BASIS = NavCalculator.FULL_NAV;
    private static final Logger log = LoggerFactory.getLogger(ValuationService.class);

    private final ValuationRepository repository;
    private final LedgerRepository ledger;
    private final MarketDataRepository marketData;
    private final PriceQueryService prices;
    private final ProcessedEvents processedEvents;
    private final OutboxWriter outbox;
    private final MarketClock clock;

    public ValuationService(ValuationRepository repository, LedgerRepository ledger, MarketDataRepository marketData, PriceQueryService prices,
                            ProcessedEvents processedEvents, OutboxWriter outbox, MarketClock clock) {
        this.repository = repository;
        this.ledger = ledger;
        this.marketData = marketData;
        this.prices = prices;
        this.processedEvents = processedEvents;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public void onPositionUpdated(EventEnvelope<PositionEvent> event) {
        if (processedEvents.markProcessed(event.eventId())) {
            repository.upsertPosition(event.payload());
        }
    }

    @Transactional(readOnly = true)
    public PortfolioValuation value(long portfolioId, LocalDate asOfDate) {
        LocalDate date = asOfDate == null ? clock.today() : asOfDate;
        List<Holding> holdings = repository.holdings(portfolioId);
        Map<Long, Quote> quotes = new HashMap<>();
        for (Holding h : holdings) {
            marketData.instrument(h.instrumentId(), null).ifPresent(instrument -> {
                LatestPrice p = prices.latest(h.instrumentId(), null, date);
                if (p.price() != null) {
                    quotes.put(h.instrumentId(), new Quote(p.price(), p.source().name(), p.dataDate(), p.stale(),
                            p.fairValueLevel()));
                }
            });
        }
        return ValuationCalculator.value(holdings, quotes);
    }

    /** NAV on {@code date}: cash and liabilities as they stood that day, added to {@code v}'s market value. */
    @Transactional(readOnly = true)
    public Nav nav(long portfolioId, LocalDate date, PortfolioValuation v) {
        return NavCalculator.nav(v.marketValue(), ledger.cashAsOf(portfolioId, date),
                ledger.liabilitiesAsOf(portfolioId, date));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(long portfolioId, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? clock.today() : to;
        LocalDate start = from == null ? end.minusDays(90) : from;
        if (start.isAfter(end)) {
            throw new ValidationException("from: must be on or before 'to'");
        }
        return repository.history(portfolioId, start, end);
    }

    /**
     * End-of-day run for {@code date}: per portfolio, store the snapshot (once), check asset-class limits and publish
     * the events. Returns how many snapshots were written.
     */
    @Transactional
    public int runEndOfDay(LocalDate date) {
        int written = 0;
        SortedSet<Long> portfolios = new TreeSet<>(repository.portfoliosWithPositions());
        portfolios.addAll(ledger.portfoliosWithLedger());
        for (long portfolioId : portfolios) {
            PortfolioValuation v = value(portfolioId, date);
            Nav nav = nav(portfolioId, date, v);
            if (!repository.insertSnapshot(portfolioId, date, v, nav, clock.now())) {
                continue;
            }
            written++;
            outbox.append("Valuation", portfolioId + ":" + date, EventTypes.VALUATION_CALCULATED,
                    Topics.VALUATION_CALCULATED, String.valueOf(portfolioId),
                    new ValuationEvent(portfolioId, date, v.marketValue(), v.totalCost(), v.unrealizedPnl(),
                            v.realizedPnl(), nav.nav(), BASIS, v.stalePositions()));
            checkLimits(portfolioId, date, v);
        }
        log.info("End-of-day valuation for {}: {} portfolio snapshots", date, written);
        return written;
    }

    private void checkLimits(long portfolioId, LocalDate date, PortfolioValuation v) {
        Map<String, BigDecimal> limits = repository.limitsFor(portfolioId);
        for (Exposure e : v.exposureByAssetClass()) {
            BigDecimal max = limits.get(e.key());
            if (max != null && e.pctOfTotal().compareTo(max) > 0
                    && repository.recordBreach(portfolioId, e.key(), date, e.pctOfTotal(), max, e.marketValue(),
                    clock.now())) {
                log.warn("Limit breached: portfolio {} {} at {}% (max {}%)", portfolioId, e.key(), e.pctOfTotal(), max);
                outbox.append("LimitBreach", portfolioId + ":" + e.key() + ":" + date, EventTypes.LIMIT_BREACHED,
                        Topics.LIMIT_BREACHED, String.valueOf(portfolioId),
                        new LimitBreachEvent(portfolioId, e.key(), e.pctOfTotal(), max, e.marketValue(), date));
            }
        }
    }
}
