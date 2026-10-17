package com.alphaledger.valuation.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.alphaledger.common.calendar.HolidayCalendar;
import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.valuation.marketdata.PriceBatch.PriceRecord;

/** The synthetic-price rule (FR-DATA-7) and the validation applied to every price before it is stored. */
class PriceRulesTest {

    private static final MarketClock CLOCK = new MarketClock(
            Clock.fixed(Instant.parse("2026-10-15T06:00:00Z"), ZoneOffset.UTC), ZoneId.of("Asia/Kolkata"));
    private static final HolidayCalendar CALENDAR = new HolidayCalendar(List.of(LocalDate.of(2026, 10, 2),
            LocalDate.of(2026, 10, 20)));
    private static final InstrumentRef RELIANCE = new InstrumentRef(18, "INE002A01018", "RELIANCE", "Reliance",
            "EQUITY", "PRIVATE_EQUITY", false);
    private static final InstrumentRef DECCAN = new InstrumentRef(27, "ZZSYN0000001", "SYN-DECCAN", "Deccan",
            "PRIVATE", "PRIVATE_EQUITY", true);

    @Test
    void configurationCannotPutSyntheticInARealInstrumentsFallbackList() {
        MarketDataProperties bad = properties(Map.of("EQUITY", List.of(PriceSourceName.NSE_BHAVCOPY,
                PriceSourceName.SYNTHETIC)));

        assertThatThrownBy(() -> new PriceSourceRegistry(List.of(new SyntheticPriceSource()), bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fallback-order.EQUITY must not contain SYNTHETIC");
    }

    @Test
    void registryKeepsFallbackOrderAndSkipsSourcesThatDoNotExistYet() {
        PriceSource bhavcopy = fake(PriceSourceName.NSE_BHAVCOPY);
        PriceSourceRegistry registry = new PriceSourceRegistry(List.of(new SyntheticPriceSource(), bhavcopy),
                properties(Map.of("MUTUAL_FUND", List.of(PriceSourceName.AMFI_NAV, PriceSourceName.MFAPI),
                        "EQUITY", List.of(PriceSourceName.NSE_BHAVCOPY), "PRIVATE", List.of(PriceSourceName.SYNTHETIC))));

        assertThat(registry.sourcesFor("EQUITY")).containsExactly(bhavcopy);
        assertThat(registry.sourcesFor("MUTUAL_FUND")).isEmpty();
        assertThat(registry.sourcesFor("PRIVATE")).extracting(PriceSource::name).containsExactly(PriceSourceName.SYNTHETIC);
    }

    @Test
    void syntheticPricesOnlyForSyntheticInstrumentsExceptOfflineTicks() {
        PriceIngestionService offline = service(MarketDataProperties.Mode.OFFLINE);
        PriceIngestionService live = service(MarketDataProperties.Mode.LIVE);

        assertThat(offline.rejectionReason(price(RELIANCE, PriceSourceName.SYNTHETIC, "2026-10-15"), RELIANCE, false))
                .isEqualTo("SYNTHETIC prices are only allowed for synthetic instruments");
        assertThat(offline.rejectionReason(price(RELIANCE, PriceSourceName.SYNTHETIC, "2026-10-15"), RELIANCE, true))
                .isNull();
        assertThat(live.rejectionReason(price(RELIANCE, PriceSourceName.SYNTHETIC, "2026-10-15"), RELIANCE, true))
                .isEqualTo("Synthetic intraday ticks are only allowed in OFFLINE mode");
        assertThat(live.rejectionReason(price(DECCAN, PriceSourceName.CSV_UPLOAD, "2026-10-14"), DECCAN, false))
                .isEqualTo("Synthetic instruments are priced only by SYNTHETIC marks");
        assertThat(live.rejectionReason(price(DECCAN, PriceSourceName.SYNTHETIC, "2026-09-30"), DECCAN, false))
                .isNull();
    }

    @Test
    void realPricesNeedABusinessDayInThePastAndAPositivePrice() {
        PriceIngestionService live = service(MarketDataProperties.Mode.LIVE);

        assertThat(live.rejectionReason(price(RELIANCE, PriceSourceName.NSE_BHAVCOPY, "2026-10-14"), RELIANCE, false))
                .isNull();
        assertThat(live.rejectionReason(price(RELIANCE, PriceSourceName.NSE_BHAVCOPY, "2026-10-02"), RELIANCE, false))
                .isEqualTo("data_date 2026-10-02 is not a business day");
        assertThat(live.rejectionReason(price(RELIANCE, PriceSourceName.NSE_BHAVCOPY, "2026-10-16"), RELIANCE, false))
                .isEqualTo("data_date 2026-10-16 is in the future");
        assertThat(live.rejectionReason(new PriceRecord(1, RELIANCE.isin(), LocalDate.of(2026, 10, 14), Instant.now(),
                BigDecimal.ZERO, PriceSourceName.CSV_UPLOAD, null), RELIANCE, false))
                .isEqualTo("Price must be greater than zero");
        assertThat(live.rejectionReason(price(RELIANCE, PriceSourceName.CSV_UPLOAD, "2026-10-14"), null, false))
                .isEqualTo("Unknown ISIN INE002A01018");
    }

    @Test
    void stalenessCountsBusinessDaysAndQuarterEndsForSyntheticMarks() {
        PriceQueryService query = new PriceQueryService(mock(MarketDataRepository.class), CALENDAR, CLOCK,
                properties(Map.of()));

        assertThat(query.businessDaysBetween(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15))).isEqualTo(1);
        assertThat(query.businessDaysBetween(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 21))).isEqualTo(2);
        assertThat(PriceQueryService.quarterEndOnOrBefore(LocalDate.of(2026, 10, 15))).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(PriceQueryService.quarterEndOnOrBefore(LocalDate.of(2026, 9, 30))).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(PriceQueryService.quarterEndOnOrBefore(LocalDate.of(2026, 1, 5))).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    private static PriceIngestionService service(MarketDataProperties.Mode mode) {
        MarketDataProperties properties = MarketDataProperties.of(mode, false, Map.of());
        return new PriceIngestionService(mock(MarketDataRepository.class),
                new PriceSourceRegistry(List.of(), properties), CALENDAR, CLOCK, properties,
                mock(com.alphaledger.common.outbox.OutboxWriter.class), mock(PlatformTransactionManager.class));
    }

    private static MarketDataProperties properties(Map<String, List<PriceSourceName>> order) {
        return MarketDataProperties.of(MarketDataProperties.Mode.OFFLINE, false, order);
    }

    private static PriceRecord price(InstrumentRef instrument, PriceSourceName source, String date) {
        LocalDate d = LocalDate.parse(date);
        return new PriceRecord(1, instrument.isin(), d, MarketTime.marketClose(d), new BigDecimal("100.00"), source, null);
    }

    private static PriceSource fake(PriceSourceName name) {
        return new PriceSource() {
            @Override
            public PriceSourceName name() {
                return name;
            }

            @Override
            public boolean supports(InstrumentRef instrument) {
                return !instrument.synthetic();
            }

            @Override
            public PriceBatch fetch(LocalDate dataDate, Collection<InstrumentRef> instruments) {
                return new PriceBatch(name, dataDate, "fake", "x", List.of(), List.of(), false, false);
            }
        };
    }
}
