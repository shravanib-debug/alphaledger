package com.alphaledger.position.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds a holding from its trades (prd.md business rule 1). Trades are applied in trade-date order (then trade ID),
 * cancelled trades are ignored, and the whole position is recomputed every time: this makes cancels exact even after
 * later trades, and makes redelivered events harmless.
 * <ul>
 * <li>BUY: {@code quantity += q}; {@code avgCost = (oldQty x avgCost + q x price) / newQty}.</li>
 * <li>SELL: {@code quantity -= q}; average cost unchanged; {@code realizedPnl += (price - avgCost) x q}.</li>
 * <li>A SELL above the holding is skipped and reported, never applied (no short selling in v1).</li>
 * </ul>
 */
public final class PositionCalculator {

    static final int COST_SCALE = 6;

    private PositionCalculator() {
    }

    public record AppliedTrade(long tradeId, String side, BigDecimal quantity, BigDecimal price, LocalDate tradeDate,
                               boolean isCancelled) {

        AppliedTrade cancelled() {
            return new AppliedTrade(tradeId, side, quantity, price, tradeDate, true);
        }
    }

    public record Skipped(long tradeId, String reason) {
    }

    public record PositionState(BigDecimal quantity, BigDecimal avgCost, BigDecimal realizedPnl, long lastTradeId,
                                List<Skipped> skipped) {

        public boolean isClosed() {
            return quantity.signum() == 0;
        }
    }

    public static PositionState fold(List<AppliedTrade> trades) {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal avgCost = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        long lastTradeId = 0;
        List<Skipped> skipped = new ArrayList<>();

        List<AppliedTrade> ordered = trades.stream()
                .sorted(Comparator.comparing(AppliedTrade::tradeDate).thenComparingLong(AppliedTrade::tradeId))
                .toList();
        for (AppliedTrade t : ordered) {
            lastTradeId = Math.max(lastTradeId, t.tradeId());
            if (t.isCancelled()) {
                continue;
            }
            if ("BUY".equals(t.side())) {
                BigDecimal newQuantity = quantity.add(t.quantity());
                avgCost = quantity.multiply(avgCost).add(t.quantity().multiply(t.price()))
                        .divide(newQuantity, COST_SCALE, RoundingMode.HALF_UP);
                quantity = newQuantity;
            } else if (t.quantity().compareTo(quantity) > 0) {
                skipped.add(new Skipped(t.tradeId(), "SELL of " + plain(t.quantity()) + " exceeds the holding of "
                        + plain(quantity)));
            } else {
                realized = realized.add(t.price().subtract(avgCost).multiply(t.quantity()));
                quantity = quantity.subtract(t.quantity());
                if (quantity.signum() == 0) {
                    avgCost = BigDecimal.ZERO;
                }
            }
        }
        return new PositionState(quantity, avgCost, realized.setScale(COST_SCALE, RoundingMode.HALF_UP), lastTradeId,
                List.copyOf(skipped));
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
