package com.alphaledger.position.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.alphaledger.position.domain.PositionCalculator.AppliedTrade;
import com.alphaledger.position.domain.PositionCalculator.PositionState;

/** Position math (prd.md business rule 1): weighted-average cost on BUY, realized P&L on SELL, exact cancels. */
class PositionCalculatorTest {

    private static final LocalDate D1 = LocalDate.of(2026, 10, 12);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 13);
    private static final LocalDate D3 = LocalDate.of(2026, 10, 14);

    @Test
    void buysAccumulateAtWeightedAverageCost() {
        PositionState state = PositionCalculator.fold(List.of(buy(1, D1, "100", "1000"), buy(2, D2, "50", "1100")));

        assertThat(state.quantity()).isEqualByComparingTo("150");
        assertThat(state.avgCost()).isEqualByComparingTo("1033.333333");   // (100 000 + 55 000) / 150
        assertThat(state.realizedPnl()).isEqualByComparingTo("0");
        assertThat(state.lastTradeId()).isEqualTo(2);
    }

    @Test
    void partialSellRealizesPnlAndKeepsAverageCost() {
        PositionState state = PositionCalculator.fold(List.of(buy(1, D1, "200", "500"), sell(2, D2, "80", "560")));

        assertThat(state.quantity()).isEqualByComparingTo("120");
        assertThat(state.avgCost()).isEqualByComparingTo("500");
        assertThat(state.realizedPnl()).isEqualByComparingTo("4800");      // (560 - 500) x 80
    }

    @Test
    void sellingAtALossRealizesANegativePnl() {
        PositionState state = PositionCalculator.fold(List.of(buy(1, D1, "10", "435.00"), sell(2, D2, "4", "420.50")));

        assertThat(state.realizedPnl()).isEqualByComparingTo("-58");       // (420.50 - 435) x 4
    }

    @Test
    void fullSellClosesThePositionAndResetsAverageCost() {
        PositionState state = PositionCalculator.fold(List.of(buy(1, D1, "100", "1000"), sell(2, D2, "100", "1200")));

        assertThat(state.quantity()).isEqualByComparingTo("0");
        assertThat(state.avgCost()).isEqualByComparingTo("0");
        assertThat(state.realizedPnl()).isEqualByComparingTo("20000");
        assertThat(state.isClosed()).isTrue();
    }

    @Test
    void cancelledTradesAreIgnoredAsIfTheyNeverHappened() {
        PositionState state = PositionCalculator.fold(List.of(buy(1, D1, "100", "1000"),
                buy(2, D2, "100", "1200").cancelled()));

        assertThat(state.quantity()).isEqualByComparingTo("100");
        assertThat(state.avgCost()).isEqualByComparingTo("1000");
    }

    @Test
    void cancellingAnEarlierBuyRecomputesLaterSellPnlExactly() {
        // BUY 100 @ 1000, BUY 100 @ 1200 (avg 1100), SELL 50 @ 1300 -> realized 10 000.
        // Cancelling the first BUY leaves BUY 100 @ 1200 then SELL 50 @ 1300 -> realized 5 000.
        PositionState state = PositionCalculator.fold(List.of(buy(1, D1, "100", "1000").cancelled(),
                buy(2, D1, "100", "1200"), sell(3, D2, "50", "1300")));

        assertThat(state.quantity()).isEqualByComparingTo("50");
        assertThat(state.avgCost()).isEqualByComparingTo("1200");
        assertThat(state.realizedPnl()).isEqualByComparingTo("5000");
    }

    @Test
    void tradesAreAppliedInTradeDateOrderNotArrivalOrder() {
        // A back-dated BUY that arrives after a SELL still counts as held before it.
        PositionState state = PositionCalculator.fold(List.of(sell(5, D2, "30", "110"), buy(9, D1, "50", "100")));

        assertThat(state.quantity()).isEqualByComparingTo("20");
        assertThat(state.realizedPnl()).isEqualByComparingTo("300");
        assertThat(state.skipped()).isEmpty();
    }

    @Test
    void aSellAboveTheHoldingIsSkippedAndReportedNeverMadeNegative() {
        PositionState state = PositionCalculator.fold(List.of(buy(1, D1, "10", "100"), sell(2, D2, "25", "110"),
                sell(3, D3, "4", "120")));

        assertThat(state.quantity()).isEqualByComparingTo("6");
        assertThat(state.realizedPnl()).isEqualByComparingTo("80");        // only the valid SELL of 4
        assertThat(state.skipped()).singleElement().satisfies(skip -> {
            assertThat(skip.tradeId()).isEqualTo(2);
            assertThat(skip.reason()).isEqualTo("SELL of 25 exceeds the holding of 10");
        });
    }

    @Test
    void fractionalMutualFundUnitsKeepPrecision() {
        PositionState state = PositionCalculator.fold(List.of(buy(1, D1, "1234.5678", "35.0155"),
                buy(2, D2, "100.0001", "35.2001")));

        assertThat(state.quantity()).isEqualByComparingTo("1334.5679");
        // (1234.5678 x 35.0155 + 100.0001 x 35.2001) / 1334.5679 = 46749.02232091 / 1334.5679 = 35.0293322...
        assertThat(state.avgCost()).isEqualByComparingTo("35.029332");
    }

    @Test
    void noTradesMeansAnEmptyPosition() {
        PositionState state = PositionCalculator.fold(List.of());

        assertThat(state.quantity()).isEqualByComparingTo("0");
        assertThat(state.lastTradeId()).isZero();
    }

    private static AppliedTrade buy(long id, LocalDate date, String qty, String price) {
        return new AppliedTrade(id, "BUY", new BigDecimal(qty), new BigDecimal(price), date, false);
    }

    private static AppliedTrade sell(long id, LocalDate date, String qty, String price) {
        return new AppliedTrade(id, "SELL", new BigDecimal(qty), new BigDecimal(price), date, false);
    }
}
