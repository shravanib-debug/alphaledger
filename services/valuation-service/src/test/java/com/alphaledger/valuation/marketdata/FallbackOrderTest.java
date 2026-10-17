package com.alphaledger.valuation.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.alphaledger.common.calendar.BusinessDayCalendar;
import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.outbox.OutboxWriter;

/** The daily run's fallback order and gap filling, and the network guard. */
class FallbackOrderTest {

    private static final LocalDate OCT_16 = LocalDate.of(2026, 10, 16);
    private static final InstrumentRef FUND_A = new InstrumentRef(14, "INF179K01XD8", null, "A", "MUTUAL_FUND",
            "CREDIT", false, "118987");
    private static final InstrumentRef FUND_B = new InstrumentRef(15, "INF109K016B1", null, "B", "MUTUAL_FUND",
            "CREDIT", false, "120692");
    private static final InstrumentRef RELIANCE = new InstrumentRef(18, "INE002A01018", "RELIANCE", "Reliance",
            "EQUITY", "PRIVATE_EQUITY", false);

    /** A source that records what it was asked for and "prices" a fixed set of instruments. */
    private static final class RecordingSource implements PriceSource {
        private final PriceSourceName name;
        private final List<Collection<InstrumentRef>> asked = new ArrayList<>();

        RecordingSource(PriceSourceName name) {
            this.name = name;
        }

        @Override
        public PriceSourceName name() {
            return name;
        }

        @Override
        public boolean supports(InstrumentRef instrument) {
            return true;
        }

        @Override
        public PriceBatch fetch(LocalDate dataDate, Collection<InstrumentRef> instruments) {
            asked.add(List.copyOf(instruments));
            return new PriceBatch(name, dataDate, name + ".file", name + "-checksum", List.of(), List.of(), false, false);
        }
    }

    @Test
    void aLaterSourceOnlyFillsTheGapsLeftByAnEarlierOne() {
        RecordingSource amfi = new RecordingSource(PriceSourceName.AMFI_NAV);
        RecordingSource mfapi = new RecordingSource(PriceSourceName.MFAPI);
        RecordingSource nse = new RecordingSource(PriceSourceName.NSE_BHAVCOPY);
        MarketDataRepository repository = mock(MarketDataRepository.class);
        when(repository.instruments()).thenReturn(List.of(FUND_A, FUND_B, RELIANCE));
        // After AMFI has run, only fund A has an AMFI price for the day.
        when(repository.pricedOn(eq(OCT_16), anyCollection())).thenAnswer(inv -> {
            Collection<?> sources = inv.getArgument(1);
            return sources.contains(PriceSourceName.AMFI_NAV) ? List.of(FUND_A.instrumentId()) : List.of();
        });
        when(repository.alreadyLoaded(any(), any())).thenReturn(true);   // ingestion itself is not under test here
        PriceIngestionService service = service(repository, Map.of(
                "MUTUAL_FUND", List.of(PriceSourceName.AMFI_NAV, PriceSourceName.MFAPI),
                "EQUITY", List.of(PriceSourceName.NSE_BHAVCOPY)), amfi, mfapi, nse);

        service.runDaily(OCT_16);

        assertThat(amfi.asked).containsExactly(List.of(FUND_A, FUND_B));
        assertThat(mfapi.asked).containsExactly(List.of(FUND_B));       // gap filled by the fallback only
        assertThat(nse.asked).containsExactly(List.of(RELIANCE));
    }

    @Test
    void aSourceWithNoFileIsLoggedAsFailedAndWritesNoPrice() {
        PriceSource failing = new PriceSource() {
            @Override
            public PriceSourceName name() {
                return PriceSourceName.NSE_BHAVCOPY;
            }

            @Override
            public boolean supports(InstrumentRef instrument) {
                return true;
            }

            @Override
            public PriceBatch fetch(LocalDate dataDate, Collection<InstrumentRef> instruments) {
                throw new PriceFileUnavailableException("No NSE bhavcopy for " + dataDate);
            }
        };
        MarketDataRepository repository = mock(MarketDataRepository.class);
        when(repository.instruments()).thenReturn(List.of(RELIANCE));
        when(repository.insertLog(any(), eq("FAILED"), any())).thenReturn(7L);
        PriceIngestionService service = service(repository, Map.of("EQUITY", List.of(PriceSourceName.NSE_BHAVCOPY)),
                failing);

        PriceIngestionService.IngestionSummary summary = service.runSource(failing, LocalDate.of(2026, 10, 20));

        assertThat(summary.status()).isEqualTo("FAILED");
        assertThat(summary.ingestionId()).isEqualTo(7L);
        verify(repository).completeLog(eq(7L), eq("FAILED"), eq(0), eq(0), eq(0),
                eq("No NSE bhavcopy for 2026-10-20"), any());
        verify(repository, never()).upsertPrice(any(), any(), anyLong());
    }

    @Test
    void theGuardRefusesHttpUnlessLiveWithTheNetworkOn() {
        for (MarketDataProperties properties : List.of(
                MarketDataProperties.of(MarketDataProperties.Mode.OFFLINE, true, Map.of()),
                MarketDataProperties.of(MarketDataProperties.Mode.LIVE, false, Map.of()))) {
            assertThatThrownBy(() -> new MarketDataHttp(properties).get("https://nsearchives.nseindia.com/x.zip"))
                    .isInstanceOf(MarketDataHttp.NetworkDisabledException.class);
        }
        assertThat(MarketDataProperties.of(MarketDataProperties.Mode.LIVE, true, Map.of()).networkAllowed()).isTrue();
    }

    /** The build-wide offline guard (root pom argLine): any JVM HTTP request to an external host fails fast. */
    @Test
    void testsCannotReachExternalHosts() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://example.com/")).build();

        assertThat(System.getProperty("http.proxyHost")).isEqualTo("127.0.0.1");
        assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(java.io.IOException.class);
    }

    private static PriceIngestionService service(MarketDataRepository repository,
                                                 Map<String, List<PriceSourceName>> order, PriceSource... sources) {
        MarketDataProperties properties = MarketDataProperties.of(MarketDataProperties.Mode.OFFLINE, false, order);
        BusinessDayCalendar calendar = mock(BusinessDayCalendar.class);
        when(calendar.isBusinessDay(any())).thenReturn(true);
        MarketClock clock = new MarketClock(java.time.Clock.fixed(java.time.Instant.parse("2026-10-17T06:00:00Z"),
                java.time.ZoneOffset.UTC), java.time.ZoneId.of("Asia/Kolkata"));
        return new PriceIngestionService(repository, new PriceSourceRegistry(List.of(sources), properties), calendar,
                clock, properties, mock(OutboxWriter.class), mock(PlatformTransactionManager.class));
    }
}
