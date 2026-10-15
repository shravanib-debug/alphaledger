package com.alphaledger.valuation.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.alphaledger.common.error.ValidationException;

/** The committed seed prices, the CSV upload format, and the synthetic marks and ticks. */
class PriceFilesTest {

    private static final List<InstrumentRef> SYNTHETICS = List.of(
            new InstrumentRef(27, "ZZSYN0000001", null, "Deccan", "PRIVATE", "PRIVATE_EQUITY", true),
            new InstrumentRef(28, "ZZSYN0000002", null, "Konkan", "PRIVATE", "PRIVATE_EQUITY", true),
            new InstrumentRef(29, "ZZSYN0000003", null, "Malabar", "PRIVATE", "PRIVATE_EQUITY", true));

    @Test
    void committedSeedHasRealPricesForAll26InstrumentsWithProvenance() throws Exception {
        PriceBatch batch;
        try (InputStream in = new ClassPathResource("reference-data/sample/prices/seed_closes_2026-10-14.csv")
                .getInputStream()) {
            batch = CsvPriceFile.parse("seed_closes_2026-10-14.csv", in.readAllBytes());
        }

        assertThat(batch.rejected()).isEmpty();
        assertThat(batch.records()).hasSize(26)
                .allSatisfy(r -> assertThat(r.dataDate()).isEqualTo(LocalDate.of(2026, 10, 14)));
        assertThat(batch.records()).filteredOn(r -> r.source() == PriceSourceName.NSE_BHAVCOPY).hasSize(19);
        assertThat(batch.records()).filteredOn(r -> r.source() == PriceSourceName.AMFI_NAV).hasSize(7);
        assertThat(batch.records()).filteredOn(r -> r.isin().equals("INE041025011"))
                .singleElement().satisfies(r -> assertThat(r.price()).isEqualByComparingTo("434.91"));
    }

    @Test
    void uploadRowsAreCheckedIndividually() {
        String csv = """
                isin,data_date,price,source
                ine002a01018,2026-10-15,1180.00,
                INE002A01018,2026-10-15,1180.00,SYNTHETIC
                NOT-AN-ISIN,2026-10-15,10,
                INE040A01034,15/10/2026,700,
                INE040A01034,2026-10-15,abc,
                """;
        PriceBatch batch = CsvPriceFile.parse("upload.csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThat(batch.records()).singleElement().satisfies(r -> {
            assertThat(r.isin()).isEqualTo("INE002A01018");
            assertThat(r.source()).isEqualTo(PriceSourceName.CSV_UPLOAD);
        });
        assertThat(batch.rejected()).extracting(PriceBatch.RejectedRow::rowNumber).containsExactly(3, 4, 5, 6);
        assertThat(batch.rejected().get(0).reason()).isEqualTo("source SYNTHETIC cannot be uploaded");
        assertThat(batch.rejectUnknownIsins()).isTrue();
    }

    @Test
    void uploadNeedsTheHeaderAndSameBytesGiveTheSameChecksum() {
        assertThatThrownBy(() -> CsvPriceFile.parse("x.csv", "isin;date;price\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationException.class);
        byte[] bytes = "isin,data_date,price\nINE002A01018,2026-10-14,1174.60\n".getBytes(StandardCharsets.UTF_8);
        assertThat(CsvPriceFile.parse("a.csv", bytes).checksum()).isEqualTo(CsvPriceFile.parse("b.csv", bytes).checksum());
    }

    @Test
    void syntheticMarksAreQuarterlyLevel3AndOnlyUpToTheDataDate() {
        SyntheticPriceSource source = new SyntheticPriceSource();

        PriceBatch october = source.fetch(LocalDate.of(2026, 10, 15), SYNTHETICS);
        PriceBatch august = source.fetch(LocalDate.of(2026, 8, 1), SYNTHETICS);

        assertThat(october.records()).hasSize(12)
                .allSatisfy(r -> {
                    assertThat(r.source()).isEqualTo(PriceSourceName.SYNTHETIC);
                    assertThat(r.fairValueLevel()).isEqualTo("L3");
                });
        assertThat(august.records()).hasSize(9);
        assertThat(october.checksum()).isNotEqualTo(august.checksum());
        assertThat(source.fetch(LocalDate.of(2026, 10, 16), SYNTHETICS).checksum()).isEqualTo(october.checksum());
        assertThat(source.supports(SYNTHETICS.get(0))).isTrue();
        assertThat(source.supports(new InstrumentRef(18, "INE002A01018", null, "Reliance", "EQUITY",
                "PRIVATE_EQUITY", false))).isFalse();
    }

    @Test
    void intradayTicksStayWithinHalfAPercentAndAreRepeatable() {
        SyntheticPriceSource source = new SyntheticPriceSource();
        InstrumentRef reliance = new InstrumentRef(18, "INE002A01018", null, "Reliance", "EQUITY", "PRIVATE_EQUITY",
                false);
        Map<InstrumentRef, BigDecimal> latest = new LinkedHashMap<>();
        latest.put(reliance, new BigDecimal("1174.60"));
        Instant now = Instant.parse("2026-10-15T06:00:00Z");

        PriceBatch ticks = source.intradayTicks(latest, LocalDate.of(2026, 10, 15), now);

        BigDecimal tick = ticks.records().get(0).price();
        assertThat(tick).isBetween(new BigDecimal("1168.73"), new BigDecimal("1180.48"));
        assertThat(ticks.intradayTicks()).isTrue();
        assertThat(source.intradayTicks(latest, LocalDate.of(2026, 10, 15), now).records().get(0).price())
                .isEqualByComparingTo(tick);
    }
}
