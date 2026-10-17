package com.alphaledger.valuation.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import tools.jackson.databind.json.JsonMapper;

/** The mfapi.in fallback against a local stub server; the real site is never called. */
class MfApiPriceSourceTest {

    private static final LocalDate OCT_16 = LocalDate.of(2026, 10, 16);
    private static final InstrumentRef HDFC_CORP_BOND = new InstrumentRef(14, "INF179K01XD8", null, "HDFC Corp Bond",
            "MUTUAL_FUND", "CREDIT", false, "118987");
    private static final InstrumentRef PPFAS = new InstrumentRef(24, "INF879O01027", null, "Parag Parikh",
            "MUTUAL_FUND", "PRIVATE_EQUITY", false, "122639");

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mf/", exchange -> {
            calls.incrementAndGet();
            String code = exchange.getRequestURI().getPath().substring("/mf/".length());
            String body = switch (code) {
                case "118987" -> "{\"meta\":{\"scheme_code\":118987},\"data\":[{\"date\":\"16-10-2026\",\"nav\":\"35.01820\"},"
                        + "{\"date\":\"15-10-2026\",\"nav\":\"35.01000\"}]}";
                case "122639" -> "{\"meta\":{},\"data\":[{\"date\":\"15-10-2026\",\"nav\":\"90.10000\"}]}";
                default -> "{\"meta\":{},\"data\":[]}";
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void takesTheNavForTheRequestedDateAndLeavesAGapWhenThereIsNone() {
        MfApiPriceSource source = source(true);

        PriceBatch batch = source.fetch(OCT_16, List.of(HDFC_CORP_BOND, PPFAS));

        assertThat(batch.logSource()).isEqualTo(PriceSourceName.MFAPI);
        assertThat(batch.records()).singleElement().satisfies(r -> {
            assertThat(r.isin()).isEqualTo("INF179K01XD8");
            assertThat(r.price()).isEqualByComparingTo("35.0182");
            assertThat(r.source()).isEqualTo(PriceSourceName.MFAPI);
        });
        assertThat(batch.rejected()).isEmpty();   // PPFAS has no NAV for 16-Oct yet: a gap, not an error
    }

    @Test
    void theCircuitBreakerStopsCallingAfterRepeatedFailures() {
        MfApiPriceSource source = source(true);
        status = 500;

        PriceBatch batch = source.fetch(OCT_16, List.of(HDFC_CORP_BOND, PPFAS, HDFC_CORP_BOND, PPFAS, HDFC_CORP_BOND,
                PPFAS));

        assertThat(source.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(calls.get()).isEqualTo(4);   // opens after the minimum of 4 failed calls
        assertThat(batch.rejected()).extracting(PriceBatch.RejectedRow::reason)
                .contains("mfapi.in circuit breaker is open");
        assertThat(batch.records()).isEmpty();
    }

    @Test
    void neverRunsWithoutTheNetwork() {
        MfApiPriceSource source = source(false);

        assertThat(source.supports(HDFC_CORP_BOND)).isFalse();
        assertThatThrownBy(() -> source.fetch(OCT_16, List.of(HDFC_CORP_BOND)))
                .isInstanceOf(MarketDataHttp.NetworkDisabledException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    void supportsOnlyRealMutualFundsWithASchemeCode() {
        MfApiPriceSource source = source(true);

        assertThat(source.supports(HDFC_CORP_BOND)).isTrue();
        assertThat(source.supports(new InstrumentRef(18, "INE002A01018", "RELIANCE", "Reliance", "EQUITY",
                "PRIVATE_EQUITY", false, null))).isFalse();
        assertThat(source.supports(new InstrumentRef(25, "INF179K01UT0", null, "HDFC Flexi", "MUTUAL_FUND",
                "PRIVATE_EQUITY", false, null))).isFalse();
    }

    private MfApiPriceSource source(boolean live) {
        MarketDataProperties properties = new MarketDataProperties(
                live ? MarketDataProperties.Mode.LIVE : MarketDataProperties.Mode.OFFLINE, live, 5, Map.of(), true,
                false, false, null, null, null, "http://127.0.0.1:" + server.getAddress().getPort(), false);
        return new MfApiPriceSource(new MarketDataHttp(properties), properties, JsonMapper.builder().build());
    }
}
