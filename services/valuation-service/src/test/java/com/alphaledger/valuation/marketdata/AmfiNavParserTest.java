package com.alphaledger.valuation.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** AMFI NAVAll.txt lines, including the committed (filtered, real) sample of 16-Oct-2026. */
class AmfiNavParserTest {

    private static final Set<String> HELD = Set.of("INF179K01XD8", "INF879O01027", "INF204K01XP8", "INF109K016B1",
            "INF209K01YN0", "INF179K01UT0", "INF789F01XA0");

    @Test
    void parsesEveryHeldFundInTheRealSampleAndSkipsHeadingLines() throws IOException {
        String[] lines;
        try (InputStream in = new ClassPathResource(MarketDataFiles.AMFI_SAMPLE + "NAVAll_2026-10-16.txt").getInputStream()) {
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n");
        }
        AmfiNavParser.checkHeader(lines[0]);
        List<AmfiNavParser.Line> parsed = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            parsed.add(AmfiNavParser.parse(i + 1, lines[i], HELD));
        }

        List<PriceBatch.PriceRecord> prices = parsed.stream().filter(AmfiNavParser.Price.class::isInstance)
                .map(l -> ((AmfiNavParser.Price) l).record()).toList();
        assertThat(prices).hasSize(7);
        assertThat(prices).filteredOn(p -> p.isin().equals("INF179K01XD8")).singleElement().satisfies(p -> {
            assertThat(p.price()).isEqualByComparingTo("35.0182");
            assertThat(p.dataDate()).isEqualTo(LocalDate.of(2026, 10, 16));
            assertThat(p.source()).isEqualTo(PriceSourceName.AMFI_NAV);
        });
        assertThat(parsed).filteredOn(AmfiNavParser.Skipped.class::isInstance).isNotEmpty();   // headings, AMC names
        assertThat(parsed).noneMatch(AmfiNavParser.Rejected.class::isInstance);
    }

    @Test
    void matchesAFundByEitherIsinColumn() {
        AmfiNavParser.Line line = AmfiNavParser.parse(9,
                "100001;INF000000001;INF179K01XD8;Some Fund;Direct Plan;IDCW Reinvestment;12.5;16-Oct-2026", HELD);

        assertThat(line).isInstanceOfSatisfying(AmfiNavParser.Price.class,
                p -> assertThat(p.record().isin()).isEqualTo("INF179K01XD8"));
    }

    @Test
    void skipsFundsWeDoNotHoldHeadingsAndBlankLines() {
        assertThat(AmfiNavParser.parse(2, "Open Ended Schemes(Debt Scheme - Gilt Fund)", HELD))
                .isInstanceOf(AmfiNavParser.Skipped.class);
        assertThat(AmfiNavParser.parse(3, "HDFC Mutual Fund", HELD)).isInstanceOf(AmfiNavParser.Skipped.class);
        assertThat(AmfiNavParser.parse(4, " ", HELD)).isInstanceOf(AmfiNavParser.Skipped.class);
        assertThat(AmfiNavParser.parse(5, "1;INF000000009;-;Other;Direct;Growth;10.1;16-Oct-2026", HELD))
                .isInstanceOf(AmfiNavParser.Skipped.class);
    }

    @Test
    void rejectsZeroAndNonNumericNavs() {
        AmfiNavParser.Line na = AmfiNavParser.parse(7, "118987;INF179K01XD8;-;HDFC;Direct;Growth;N.A.;16-Oct-2026", HELD);
        AmfiNavParser.Line zero = AmfiNavParser.parse(8, "118987;INF179K01XD8;-;HDFC;Direct;Growth;0.0000;16-Oct-2026", HELD);

        assertThat(na).isInstanceOfSatisfying(AmfiNavParser.Rejected.class,
                r -> assertThat(r.row().reason()).isEqualTo("NAV 'N.A.' is not a number"));
        assertThat(zero).isInstanceOfSatisfying(AmfiNavParser.Rejected.class,
                r -> assertThat(r.row().reason()).isEqualTo("NAV must be greater than zero"));
    }

    @Test
    void readsMonthNamesInAnyCase() {
        assertThat(AmfiNavParser.parse(2, "118987;INF179K01XD8;-;HDFC;Direct;Growth;35.0182;16-OCT-2026", HELD))
                .isInstanceOfSatisfying(AmfiNavParser.Price.class,
                        p -> assertThat(p.record().price()).isEqualTo(new BigDecimal("35.0182")));
    }

    @Test
    void aChangedHeaderRefusesTheWholeFile() {
        assertThatThrownBy(() -> AmfiNavParser.checkHeader("Code;ISIN;Name;NAV;Date"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("header changed");
    }
}
