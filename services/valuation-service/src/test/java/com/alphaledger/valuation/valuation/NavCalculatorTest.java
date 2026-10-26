package com.alphaledger.valuation.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.alphaledger.valuation.valuation.NavCalculator.Nav;

/**
 * NAV = market value + cash - liabilities (prd.md FR-VAL-10). Expected numbers are worked out by hand in the comments;
 * the three scenarios at the end are the Phase 4B "checked against a spreadsheet" set (docs/nav-scenarios.md).
 */
class NavCalculatorTest {

    @Test
    void cashOnly() {
        // No holdings, 1 000 000.00 deposited: NAV = 0 + 1 000 000.00 - 0.
        Nav nav = NavCalculator.nav(money("0"), money("1000000"), money("0"));

        assertThat(nav.nav()).isEqualByComparingTo("1000000.00");
        assertThat(nav.basis()).isEqualTo(NavCalculator.FULL_NAV);
    }

    @Test
    void liabilitiesOnly() {
        // No holdings or cash, a 2 500.00 expense payable: NAV = -2 500.00.
        Nav nav = NavCalculator.nav(money("0"), money("0"), money("2500"));

        assertThat(nav.nav()).isEqualByComparingTo("-2500.00");
    }

    @Test
    void mixed() {
        // Market value 157 500.00 + cash 42 500.00 - liabilities 1 200.50 = 198 799.50.
        Nav nav = NavCalculator.nav(money("157500"), money("42500"), money("1200.50"));

        assertThat(nav.marketValue()).isEqualByComparingTo("157500.00");
        assertThat(nav.cash()).isEqualByComparingTo("42500.00");
        assertThat(nav.liabilities()).isEqualByComparingTo("1200.50");
        assertThat(nav.nav()).isEqualByComparingTo("198799.50");
    }

    @Test
    void liabilitiesExceedAssetsGiveANegativeNav() {
        // Market value 64 800.00 + cash -5 000.00 (overdrawn by a settled BUY) - borrowing 100 000.00 = -40 200.00.
        // A negative NAV is reported as it is, never floored at zero.
        Nav nav = NavCalculator.nav(money("64800"), money("-5000"), money("100000"));

        assertThat(nav.nav()).isEqualByComparingTo("-40200.00");
    }

    @Test
    void componentsAreRoundedToPaise() {
        Nav nav = NavCalculator.nav(new BigDecimal("100.005"), new BigDecimal("0.004"), new BigDecimal("0.001"));

        assertThat(nav.nav()).isEqualByComparingTo("100.01");   // 100.01 + 0.00 - 0.00
        assertThat(nav.nav().scale()).isEqualTo(2);
    }

    @Test
    void dailyManagementFeeAccrualIsRateTimesMarketValueOver365() {
        // 1% a year on 432 515.00: 0.01 x 432 515.00 / 365 = 11.849726... -> 11.85.
        assertThat(NavCalculator.dailyFeeAccrual(new BigDecimal("0.01"), money("432515")))
                .isEqualByComparingTo("11.85");
        // Nothing to charge on an empty portfolio, and a zero rate charges nothing.
        assertThat(NavCalculator.dailyFeeAccrual(new BigDecimal("0.01"), money("0"))).isEqualByComparingTo("0");
        assertThat(NavCalculator.dailyFeeAccrual(BigDecimal.ZERO, money("432515"))).isEqualByComparingTo("0");
    }

    @Test
    void cashMovementOfASettledTrade() {
        // BUY 100 @ 1 033.3333 pays 103 333.33; SELL 80 @ 560 receives 44 800.00.
        assertThat(NavCalculator.settlementCash("BUY", new BigDecimal("100"), new BigDecimal("1033.3333")))
                .isEqualByComparingTo("-103333.33");
        assertThat(NavCalculator.settlementCash("SELL", new BigDecimal("80"), new BigDecimal("560")))
                .isEqualByComparingTo("44800.00");
    }

    // Spreadsheet scenarios (docs/nav-scenarios.md): trades and cash moves applied in order, then valued.

    @Test
    void spreadsheetScenario1DepositThenBuy() {
        // Deposit 200 000.00; BUY 150 @ 1 033.333333 settles: cash 200 000.00 - 155 000.00 = 45 000.00.
        // Price 1 050: market value 157 500.00. Liabilities: none. NAV = 157 500.00 + 45 000.00 = 202 500.00.
        BigDecimal cash = money("200000").add(NavCalculator.settlementCash("BUY", new BigDecimal("150"),
                new BigDecimal("1033.333333")));
        Nav nav = NavCalculator.nav(money("157500"), cash, money("0"));

        assertThat(cash).isEqualByComparingTo("45000.00");
        assertThat(nav.nav()).isEqualByComparingTo("202500.00");
    }

    @Test
    void spreadsheetScenario2BuySellAndFees() {
        // Deposit 100 000.00; BUY 200 @ 500 (-100 000.00); SELL 80 @ 560 (+44 800.00) -> cash 44 800.00.
        // Price 540: market value 120 x 540 = 64 800.00.
        // Fees at 1% a year accrued for 3 days on 64 800.00: 3 x 1.78 = 5.34; an expense payable of 500.00.
        // NAV = 64 800.00 + 44 800.00 - 505.34 = 109 094.66.
        BigDecimal cash = money("100000")
                .add(NavCalculator.settlementCash("BUY", new BigDecimal("200"), new BigDecimal("500")))
                .add(NavCalculator.settlementCash("SELL", new BigDecimal("80"), new BigDecimal("560")));
        BigDecimal dailyFee = NavCalculator.dailyFeeAccrual(new BigDecimal("0.01"), money("64800"));
        BigDecimal liabilities = dailyFee.multiply(BigDecimal.valueOf(3)).add(money("500"));
        Nav nav = NavCalculator.nav(money("64800"), cash, liabilities);

        assertThat(cash).isEqualByComparingTo("44800.00");
        assertThat(dailyFee).isEqualByComparingTo("1.78");
        assertThat(nav.nav()).isEqualByComparingTo("109094.66");
    }

    @Test
    void spreadsheetScenario3LeveragedAndUnderwater() {
        // Borrow 300 000.00 (cash +300 000.00, liability 300 000.00); BUY 1 000 @ 300 (-300 000.00) -> cash 0.
        // Price falls to 250: market value 250 000.00. NAV = 250 000.00 + 0 - 300 000.00 = -50 000.00.
        BigDecimal cash = money("300000").add(NavCalculator.settlementCash("BUY", new BigDecimal("1000"),
                new BigDecimal("300")));
        Nav nav = NavCalculator.nav(money("250000"), cash, money("300000"));

        assertThat(cash).isEqualByComparingTo("0");
        assertThat(nav.nav()).isEqualByComparingTo("-50000.00");
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
