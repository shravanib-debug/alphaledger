package com.alphaledger.valuation.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.List;

import org.springframework.stereotype.Service;

import com.alphaledger.common.calendar.BusinessDayCalendar;
import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.error.NotFoundException;
import com.alphaledger.common.error.ValidationException;
import com.alphaledger.valuation.marketdata.MarketDataRepository.StoredPrice;

/**
 * Latest price with its source badge and staleness (prd.md business rule 2): a price is stale when its data date is
 * before the expected price date, which is the latest business day on or before the as-of date (the latest
 * quarter-end for synthetic instruments). A stale price is still returned, never replaced by a synthetic one;
 * beyond {@code max-staleness-business-days} it is also flagged as a data-quality warning.
 */
@Service
public class PriceQueryService {

    private final MarketDataRepository repository;
    private final BusinessDayCalendar calendar;
    private final MarketClock clock;
    private final int maxStalenessBusinessDays;

    public PriceQueryService(MarketDataRepository repository, BusinessDayCalendar calendar, MarketClock clock,
                             MarketDataProperties properties) {
        this.repository = repository;
        this.calendar = calendar;
        this.clock = clock;
        this.maxStalenessBusinessDays = properties.maxStalenessBusinessDays();
    }

    /** {@code price} and the source fields are null when the instrument has never been priced. */
    public record LatestPrice(long instrumentId, String isin, String name, BigDecimal price, String currency,
                              LocalDate dataDate, Instant asOf, PriceSourceName source, String fairValueLevel,
                              LocalDate expectedDate, boolean stale, int staleBusinessDays,
                              boolean stalenessWarning) {
    }

    public LatestPrice latest(Long instrumentId, String isin, LocalDate asOfDate) {
        if (instrumentId == null && (isin == null || isin.isBlank())) {
            throw new ValidationException("isin: or instrumentId is required");
        }
        InstrumentRef instrument = repository.instrument(instrumentId, isin == null ? null : isin.trim().toUpperCase())
                .orElseThrow(() -> new NotFoundException("Instrument " + (instrumentId != null ? instrumentId : isin)
                        + " not found"));
        return latest(instrument, asOfDate == null ? clock.today() : asOfDate);
    }

    public List<LatestPrice> latestForAll(LocalDate asOfDate) {
        LocalDate date = asOfDate == null ? clock.today() : asOfDate;
        return repository.instruments().stream().map(i -> latest(i, date)).toList();
    }

    public List<StoredPrice> history(String isin, LocalDate from, LocalDate to) {
        InstrumentRef instrument = repository.instrument(null, isin.trim().toUpperCase())
                .orElseThrow(() -> new NotFoundException("Instrument " + isin + " not found"));
        LocalDate end = to == null ? clock.today() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        if (start.isAfter(end)) {
            throw new ValidationException("from: must be on or before 'to'");
        }
        return repository.history(instrument.instrumentId(), start, end);
    }

    LatestPrice latest(InstrumentRef instrument, LocalDate asOfDate) {
        LocalDate expected = instrument.synthetic() ? quarterEndOnOrBefore(asOfDate)
                : calendar.previousOrSameBusinessDay(asOfDate);
        StoredPrice p = repository.latestPrice(instrument.instrumentId(), asOfDate).orElse(null);
        if (p == null) {
            return new LatestPrice(instrument.instrumentId(), instrument.isin(), instrument.name(), null, "INR", null,
                    null, null, null, expected, true, -1, true);
        }
        int behind = p.dataDate().isBefore(expected) ? businessDaysBetween(p.dataDate(), expected) : 0;
        boolean warning = !instrument.synthetic() && behind > maxStalenessBusinessDays;
        return new LatestPrice(instrument.instrumentId(), instrument.isin(), instrument.name(), p.price(), p.currency(),
                p.dataDate(), p.asOf(), p.source(), p.fairValueLevel(), expected, p.dataDate().isBefore(expected),
                behind, warning);
    }

    /** Business days after {@code from}, up to and including {@code to}. */
    int businessDaysBetween(LocalDate from, LocalDate to) {
        int days = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (calendar.isBusinessDay(d)) {
                days++;
            }
        }
        return days;
    }

    static LocalDate quarterEndOnOrBefore(LocalDate date) {
        LocalDate thisQuarterEnd = date.with(IsoFields.DAY_OF_QUARTER, 1).plusMonths(3).minusDays(1);
        return thisQuarterEnd.equals(date) ? date : date.with(IsoFields.DAY_OF_QUARTER, 1).minusDays(1);
    }
}
