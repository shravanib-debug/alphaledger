package com.alphaledger.valuation.valuation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Full NAV (v1.5; prd.md FR-VAL-8 to FR-VAL-10, architecture.md §5.4a):
 * <ul>
 * <li>NAV = market value of investments + cash - liabilities;</li>
 * <li>a settled BUY pays {@code quantity x price} out of cash, a settled SELL pays it in;</li>
 * <li>management fee accrued per calendar day = {@code annual rate x market value / 365}.</li>
 * </ul>
 * NAV can be negative when liabilities exceed assets; it is reported as it is.
 */
public final class NavCalculator {

    public static final String FULL_NAV = "FULL_NAV";
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);

    private NavCalculator() {
    }

    public record Nav(BigDecimal marketValue, BigDecimal cash, BigDecimal liabilities, BigDecimal nav, String basis) {
    }

    public static Nav nav(BigDecimal marketValue, BigDecimal cash, BigDecimal liabilities) {
        BigDecimal mv = money(marketValue);
        BigDecimal c = money(cash);
        BigDecimal l = money(liabilities);
        return new Nav(mv, c, l, mv.add(c).subtract(l), FULL_NAV);
    }

    /** Signed cash movement of a settled trade: negative for BUY, positive for SELL. */
    public static BigDecimal settlementCash(String side, BigDecimal quantity, BigDecimal price) {
        BigDecimal amount = money(quantity.multiply(price));
        return "BUY".equals(side) ? amount.negate() : amount;
    }

    public static BigDecimal dailyFeeAccrual(BigDecimal annualRate, BigDecimal marketValue) {
        return annualRate.multiply(marketValue).divide(DAYS_PER_YEAR, ValuationCalculator.MONEY_SCALE,
                RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(ValuationCalculator.MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
