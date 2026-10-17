package com.alphaledger.reconciliation.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.alphaledger.reconciliation.matching.MatchingEngine.BreakType;
import com.alphaledger.reconciliation.matching.MatchingEngine.ExternalRecord;
import com.alphaledger.reconciliation.matching.MatchingEngine.Finding;
import com.alphaledger.reconciliation.matching.MatchingEngine.InternalTrade;
import com.alphaledger.reconciliation.matching.MatchingEngine.Result;

/** Matching rules (FR-REC-3 to FR-REC-5): key trade_ref + ISIN + trade_date; exact quantity; price within tolerance. */
class MatchingEngineTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 14);
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private final MatchingEngine engine = new MatchingEngine(TOLERANCE);

    @Test
    void identicalRecordsMatch() {
        Result r = engine.match(List.of(ext("T1", "INE002A01018", "BUY", "100", "1174.60")),
                List.of(internal(1, "T1", "INE002A01018", "BUY", "100", "1174.60")));

        assertThat(r.matched()).isEqualTo(1);
        assertThat(r.findings()).isEmpty();
    }

    @Test
    void custodianRowWithoutInternalTradeIsMissingInternal() {
        Result r = engine.match(List.of(ext("GHOST", "INE002A01018", "BUY", "5", "1174.60")), List.of());

        assertThat(r.findings()).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(BreakType.MISSING_INTERNAL);
            assertThat(f.internal()).isNull();
            assertThat(f.external().tradeRef()).isEqualTo("GHOST");
        });
    }

    @Test
    void internalTradeAbsentFromFileIsMissingExternal() {
        Result r = engine.match(List.of(), List.of(internal(7, "T7", "INE009A01021", "BUY", "50", "1088")));

        assertThat(r.findings()).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(BreakType.MISSING_EXTERNAL);
            assertThat(f.internal().tradeId()).isEqualTo(7);
            assertThat(f.external()).isNull();
        });
    }

    @Test
    void anyQuantityDifferenceIsAQuantityMismatch() {
        Result r = engine.match(List.of(ext("T1", "INE002A01018", "BUY", "100.0001", "1174.60")),
                List.of(internal(1, "T1", "INE002A01018", "BUY", "100", "1174.60")));

        assertThat(r.findings()).extracting(Finding::type).containsExactly(BreakType.QTY_MISMATCH);
    }

    @Test
    void oppositeSideIsAQuantityMismatch() {
        // BUY 100 vs SELL 100: signed quantities +100 and -100 differ.
        Result r = engine.match(List.of(ext("T1", "INE002A01018", "SELL", "100", "1174.60")),
                List.of(internal(1, "T1", "INE002A01018", "BUY", "100", "1174.60")));

        assertThat(r.findings()).extracting(Finding::type).containsExactly(BreakType.QTY_MISMATCH);
    }

    @ParameterizedTest(name = "internal 100.00, custodian {0} -> {1}")
    @CsvSource({
            "100.00, MATCHED",
            "100.01, MATCHED",          // exactly at the tolerance
            "99.99,  MATCHED",
            "100.011, PRICE_MISMATCH",  // just over
            "99.989, PRICE_MISMATCH",
            "101.00, PRICE_MISMATCH"
    })
    void priceIsComparedWithinTheTolerance(String externalPrice, String expected) {
        Result r = engine.match(List.of(ext("T1", "INE002A01018", "BUY", "10", externalPrice)),
                List.of(internal(1, "T1", "INE002A01018", "BUY", "10", "100.00")));

        if ("MATCHED".equals(expected)) {
            assertThat(r.matched()).isEqualTo(1);
            assertThat(r.findings()).isEmpty();
        } else {
            assertThat(r.findings()).extracting(Finding::type).containsExactly(BreakType.valueOf(expected));
        }
    }

    @Test
    void quantityIsCheckedBeforePrice() {
        Result r = engine.match(List.of(ext("T1", "INE002A01018", "BUY", "99", "120")),
                List.of(internal(1, "T1", "INE002A01018", "BUY", "100", "100")));

        assertThat(r.findings()).extracting(Finding::type).containsExactly(BreakType.QTY_MISMATCH);
    }

    @Test
    void theKeyIncludesIsinAndTradeDate() {
        // Same trade reference, different ISIN: the custodian row and the internal trade do not match each other.
        Result r = engine.match(List.of(ext("T1", "INE040A01034", "BUY", "10", "700")),
                List.of(internal(1, "T1", "INE002A01018", "BUY", "10", "700")));

        assertThat(r.findings()).extracting(Finding::type)
                .containsExactlyInAnyOrder(BreakType.MISSING_INTERNAL, BreakType.MISSING_EXTERNAL);
    }

    @Test
    void mixedFileProducesEachBreakTypeAndCountsMatches() {
        Result r = engine.match(
                List.of(ext("A", "I1AAAAAAAAA1", "BUY", "10", "100"),    // match
                        ext("B", "I1AAAAAAAAA1", "BUY", "12", "100"),    // qty
                        ext("C", "I1AAAAAAAAA1", "BUY", "10", "105"),    // price
                        ext("X", "I1AAAAAAAAA1", "BUY", "1", "100")),    // missing internal
                List.of(internal(1, "A", "I1AAAAAAAAA1", "BUY", "10", "100"),
                        internal(2, "B", "I1AAAAAAAAA1", "BUY", "10", "100"),
                        internal(3, "C", "I1AAAAAAAAA1", "BUY", "10", "100"),
                        internal(4, "D", "I1AAAAAAAAA1", "BUY", "10", "100")));   // missing external

        assertThat(r.matched()).isEqualTo(1);
        assertThat(r.count(BreakType.QTY_MISMATCH)).isEqualTo(1);
        assertThat(r.count(BreakType.PRICE_MISMATCH)).isEqualTo(1);
        assertThat(r.count(BreakType.MISSING_INTERNAL)).isEqualTo(1);
        assertThat(r.count(BreakType.MISSING_EXTERNAL)).isEqualTo(1);
    }

    private static ExternalRecord ext(String ref, String isin, String side, String qty, String price) {
        return new ExternalRecord(1, ref, isin, D, side, new BigDecimal(qty), new BigDecimal(price));
    }

    private static InternalTrade internal(long id, String ref, String isin, String side, String qty, String price) {
        return new InternalTrade(id, ref, 2L, isin, D, side, new BigDecimal(qty), new BigDecimal(price));
    }
}
