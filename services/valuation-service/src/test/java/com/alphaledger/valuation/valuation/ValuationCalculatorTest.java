package com.alphaledger.valuation.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.alphaledger.valuation.valuation.ValuationCalculator.Holding;
import com.alphaledger.valuation.valuation.ValuationCalculator.PortfolioValuation;
import com.alphaledger.valuation.valuation.ValuationCalculator.Quote;

/**
 * Market value, P&L and exposure (prd.md business rules 2 and 3). The three scenarios are the Phase 4 Definition of
 * Done "checked against a spreadsheet"; the expected numbers are worked out by hand in the comments.
 */
class ValuationCalculatorTest {

    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 10, 14);

    @Test
    void scenario1BuysOnly() {
        // BUY 100 @ 1000, BUY 50 @ 1100 -> 150 units, avg cost 1033.333333 (position-service).
        // Price 1050: market value 150 x 1050 = 157 500.00; cost 150 x 1033.333333 = 155 000.00;
        // unrealized 157 500.00 - 155 000.00 = 2 500.00.
        PortfolioValuation v = value(List.of(holding(18, "PRIVATE_EQUITY", "150", "1033.333333", "0")),
                Map.of(18L, quote("1050")));

        assertThat(v.marketValue()).isEqualByComparingTo("157500.00");
        assertThat(v.totalCost()).isEqualByComparingTo("155000.00");
        assertThat(v.unrealizedPnl()).isEqualByComparingTo("2500.00");
        assertThat(v.realizedPnl()).isEqualByComparingTo("0");
    }

    @Test
    void scenario2PartialSell() {
        // BUY 200 @ 500, SELL 80 @ 560 -> 120 units, avg cost 500, realized (560 - 500) x 80 = 4 800.
        // Price 540: market value 120 x 540 = 64 800.00; unrealized (540 - 500) x 120 = 4 800.00.
        PortfolioValuation v = value(List.of(holding(1, "REAL_ESTATE", "120", "500", "4800")),
                Map.of(1L, quote("540")));

        assertThat(v.marketValue()).isEqualByComparingTo("64800.00");
        assertThat(v.unrealizedPnl()).isEqualByComparingTo("4800.00");
        assertThat(v.realizedPnl()).isEqualByComparingTo("4800.00");
    }

    @Test
    void scenario3BuyThenCancel() {
        // BUY 100 @ 1000, BUY 100 @ 1200, second BUY cancelled -> 100 units @ 1000, nothing realized.
        // Price 1100: market value 110 000.00; unrealized (1100 - 1000) x 100 = 10 000.00.
        PortfolioValuation v = value(List.of(holding(9, "CREDIT", "100", "1000", "0")), Map.of(9L, quote("1100")));

        assertThat(v.marketValue()).isEqualByComparingTo("110000.00");
        assertThat(v.unrealizedPnl()).isEqualByComparingTo("10000.00");
    }

    @Test
    void exposureByAssetClassAndInstrumentAddsUpTo100Percent() {
        // Real estate 500 x 434.91 = 217 455.00; credit 1000 x 97.60 = 97 600.00; PE 100 x 1174.60 = 117 460.00.
        // Total 432 515.00 -> 50.28%, 22.57%, 27.16% (rounded; the remainder stays within 0.01%).
        PortfolioValuation v = value(List.of(
                holding(1, "REAL_ESTATE", "500", "430", "0"),
                holding(9, "CREDIT", "1000", "98", "0"),
                holding(18, "PRIVATE_EQUITY", "100", "1200", "0")),
                Map.of(1L, quote("434.91"), 9L, quote("97.60"), 18L, quote("1174.60")));

        assertThat(v.marketValue()).isEqualByComparingTo("432515.00");
        assertThat(v.exposureByAssetClass()).extracting(ValuationCalculator.Exposure::key)
                .containsExactly("REAL_ESTATE", "PRIVATE_EQUITY", "CREDIT");
        assertThat(v.exposureByAssetClass().get(0).pctOfTotal()).isEqualByComparingTo("50.28");
        assertThat(v.exposureByAssetClass().get(2).pctOfTotal()).isEqualByComparingTo("22.57");
        assertThat(v.exposureByInstrument()).hasSize(3);
        // Unrealized: (217 455 - 215 000) + (97 600 - 98 000) + (117 460 - 120 000) = 2 455 - 400 - 2 540 = -485.
        assertThat(v.unrealizedPnl()).isEqualByComparingTo("-485.00");
    }

    @Test
    void unpricedHoldingsAreReportedNotValuedAtZero() {
        PortfolioValuation v = value(List.of(holding(18, "PRIVATE_EQUITY", "10", "1000", "0"),
                holding(19, "PRIVATE_EQUITY", "5", "700", "0")), Map.of(18L, quote("1100")));

        assertThat(v.marketValue()).isEqualByComparingTo("11000.00");
        assertThat(v.unpricedPositions()).isEqualTo(1);
        assertThat(v.lines()).filteredOn(l -> l.instrumentId() == 19).singleElement()
                .satisfies(l -> assertThat(l.marketValue()).isNull());
    }

    @Test
    void stalePricesAreCountedAndClosedPositionsKeepTheirRealizedPnl() {
        Quote stale = new Quote(new BigDecimal("1100"), "NSE_BHAVCOPY", PRICE_DATE, true, null);
        PortfolioValuation v = value(List.of(holding(18, "PRIVATE_EQUITY", "10", "1000", "0"),
                holding(20, "PRIVATE_EQUITY", "0", "0", "2500")), Map.of(18L, stale));

        assertThat(v.stalePositions()).isEqualTo(1);
        assertThat(v.realizedPnl()).isEqualByComparingTo("2500.00");
        assertThat(v.lines()).hasSize(1);   // closed position contributes realized P&L only
    }

    private static PortfolioValuation value(List<Holding> holdings, Map<Long, Quote> quotes) {
        return ValuationCalculator.value(holdings, quotes);
    }

    private static Holding holding(long instrumentId, String assetClass, String qty, String avgCost, String realized) {
        return new Holding(instrumentId, "ISIN" + instrumentId, "Instrument " + instrumentId, assetClass,
                new BigDecimal(qty), new BigDecimal(avgCost), new BigDecimal(realized));
    }

    private static Quote quote(String price) {
        return new Quote(new BigDecimal(price), "NSE_BHAVCOPY", PRICE_DATE, false, null);
    }
}
