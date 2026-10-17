package com.alphaledger.valuation.marketdata;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.alphaledger.valuation.marketdata.PriceBatch.PriceRecord;
import com.alphaledger.valuation.marketdata.PriceBatch.RejectedRow;

/**
 * Fallback for mutual-fund NAVs that AMFI did not give us (architecture.md §5.3): mfapi.in's
 * {@code /mf/{schemeCode}} history, by the instrument's AMFI scheme code. Never required: it runs only in LIVE mode
 * with the network on, at most {@value #CALLS_PER_SECOND} calls a second, behind a circuit breaker that stops calling
 * after repeated failures. A fund it cannot price keeps its last real price, flagged stale.
 */
@Component
public class MfApiPriceSource implements PriceSource {

    static final int CALLS_PER_SECOND = 2;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final MarketDataHttp http;
    private final MarketDataProperties properties;
    private final JsonMapper jsonMapper;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;

    public MfApiPriceSource(MarketDataHttp http, MarketDataProperties properties, JsonMapper jsonMapper) {
        this.http = http;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.circuitBreaker = CircuitBreaker.of("mfapi", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(5))
                .ignoreExceptions(MarketDataHttp.NetworkDisabledException.class)
                .build());
        this.rateLimiter = RateLimiter.of("mfapi", RateLimiterConfig.custom()
                .limitForPeriod(CALLS_PER_SECOND)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(5))
                .build());
    }

    @Override
    public PriceSourceName name() {
        return PriceSourceName.MFAPI;
    }

    /** Mutual funds with a scheme code, and only when the network may be used. */
    @Override
    public boolean supports(InstrumentRef instrument) {
        return properties.networkAllowed() && !instrument.synthetic()
                && "MUTUAL_FUND".equals(instrument.instrumentType())
                && instrument.amfiSchemeCode() != null && !instrument.amfiSchemeCode().isBlank();
    }

    @Override
    public PriceBatch fetch(LocalDate dataDate, Collection<InstrumentRef> instruments) {
        List<PriceRecord> records = new ArrayList<>();
        List<RejectedRow> rejected = new ArrayList<>();
        StringBuilder fingerprint = new StringBuilder("mfapi|" + dataDate);
        int row = 0;
        for (InstrumentRef instrument : instruments) {
            row++;
            String url = properties.mfapiUrl() + "/mf/" + instrument.amfiSchemeCode();
            try {
                byte[] body = call(() -> http.get(url));
                BigDecimal nav = navOn(body, dataDate);
                if (nav == null) {
                    continue;   // no NAV for that date (yet): a gap, not an error
                }
                if (nav.signum() <= 0) {
                    rejected.add(new RejectedRow(row, instrument.isin(), "NAV must be greater than zero", null));
                    continue;
                }
                records.add(new PriceRecord(row, instrument.isin(), dataDate, MarketTime.marketClose(dataDate), nav,
                        PriceSourceName.MFAPI, null));
                fingerprint.append('|').append(instrument.isin()).append('=').append(nav.toPlainString());
            } catch (CallNotPermittedException ex) {
                rejected.add(new RejectedRow(row, instrument.isin(), "mfapi.in circuit breaker is open", null));
            } catch (RequestNotPermitted ex) {
                rejected.add(new RejectedRow(row, instrument.isin(), "mfapi.in rate limit reached", null));
            } catch (MarketDataHttp.NetworkDisabledException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                rejected.add(new RejectedRow(row, instrument.isin(), "mfapi.in: " + ex.getMessage(), null));
            }
        }
        return new PriceBatch(PriceSourceName.MFAPI, dataDate, "mfapi.in", MarketTime.sha256(fingerprint.toString()),
                records, rejected, false, false);
    }

    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    private byte[] call(Supplier<byte[]> request) {
        return circuitBreaker.executeSupplier(RateLimiter.decorateSupplier(rateLimiter, request));
    }

    /** {@code {"meta": {...}, "data": [{"date": "16-10-2026", "nav": "35.01820"}, ...]}}; newest first. */
    private BigDecimal navOn(byte[] body, LocalDate dataDate) {
        JsonNode data = jsonMapper.readTree(new String(body, StandardCharsets.UTF_8)).path("data");
        String wanted = dataDate.format(DATE);
        for (JsonNode entry : data) {
            if (wanted.equals(entry.path("date").asString())) {
                try {
                    return new BigDecimal(entry.path("nav").asString().trim());
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("NAV '" + entry.path("nav").asString() + "' is not a number");
                }
            }
        }
        return null;
    }
}
