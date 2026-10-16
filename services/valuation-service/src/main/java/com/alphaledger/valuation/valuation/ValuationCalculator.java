package com.alphaledger.valuation.valuation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Portfolio Market Value (v1; prd.md business rules 2 and 3):
 * <ul>
 * <li>market value of a position = quantity x latest price; portfolio market value = the sum;</li>
 * <li>unrealized P&L = market value - quantity x average cost; realized P&L comes from position-service;</li>
 * <li>exposure = each asset class's (and instrument's) share of market value.</li>
 * </ul>
 * This is not NAV: cash and liabilities arrive in v1.5. A holding without any price is reported as unpriced and left
 * out of the totals rather than valued at zero; stale prices are used and counted.
 */
public final class ValuationCalculator {

    static final int MONEY_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ValuationCalculator() {
    }

    public record Holding(long instrumentId, String isin, String name, String assetClass, BigDecimal quantity,
                          BigDecimal avgCost, BigDecimal realizedPnl) {
    }

    /** The price used for a holding, with its source badge. */
    public record Quote(BigDecimal price, String source, LocalDate dataDate, boolean stale, String fairValueLevel) {
    }

    public record Line(long instrumentId, String isin, String name, String assetClass, BigDecimal quantity,
                       BigDecimal avgCost, BigDecimal price, String priceSource, LocalDate priceDate,
                       boolean priceStale, String fairValueLevel, BigDecimal marketValue, BigDecimal cost,
                       BigDecimal unrealizedPnl, BigDecimal realizedPnl, BigDecimal pctOfTotal) {
    }

    public record Exposure(String key, String label, BigDecimal marketValue, BigDecimal pctOfTotal) {
    }

    public record PortfolioValuation(BigDecimal marketValue, BigDecimal totalCost, BigDecimal unrealizedPnl,
                                     BigDecimal realizedPnl, List<Line> lines, List<Exposure> exposureByAssetClass,
                                     List<Exposure> exposureByInstrument, int pricedPositions, int stalePositions,
                                     int unpricedPositions) {
    }

    public static PortfolioValuation value(List<Holding> holdings, Map<Long, Quote> quotes) {
        BigDecimal marketValue = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        int priced = 0;
        int stale = 0;
        int unpriced = 0;
        List<Line> draft = new ArrayList<>();

        for (Holding h : holdings) {
            realized = realized.add(h.realizedPnl());
            if (h.quantity().signum() == 0) {
                continue;   // closed: contributes realized P&L only
            }
            BigDecimal cost = money(h.quantity().multiply(h.avgCost()));
            Quote q = quotes.get(h.instrumentId());
            if (q == null || q.price() == null) {
                unpriced++;
                draft.add(new Line(h.instrumentId(), h.isin(), h.name(), h.assetClass(), h.quantity(), h.avgCost(),
                        null, null, null, true, null, null, cost, null, money(h.realizedPnl()), null));
                continue;
            }
            BigDecimal mv = money(h.quantity().multiply(q.price()));
            priced++;
            if (q.stale()) {
                stale++;
            }
            marketValue = marketValue.add(mv);
            totalCost = totalCost.add(cost);
            draft.add(new Line(h.instrumentId(), h.isin(), h.name(), h.assetClass(), h.quantity(), h.avgCost(),
                    q.price(), q.source(), q.dataDate(), q.stale(), q.fairValueLevel(), mv, cost, mv.subtract(cost),
                    money(h.realizedPnl()), null));
        }

        BigDecimal total = marketValue;
        List<Line> lines = draft.stream().map(l -> l.marketValue() == null ? l : new Line(l.instrumentId(), l.isin(),
                l.name(), l.assetClass(), l.quantity(), l.avgCost(), l.price(), l.priceSource(), l.priceDate(),
                l.priceStale(), l.fairValueLevel(), l.marketValue(), l.cost(), l.unrealizedPnl(), l.realizedPnl(),
                pct(l.marketValue(), total))).toList();

        Map<String, BigDecimal> byClass = new LinkedHashMap<>();
        lines.stream().filter(l -> l.marketValue() != null)
                .forEach(l -> byClass.merge(l.assetClass(), l.marketValue(), BigDecimal::add));
        List<Exposure> classExposure = byClass.entrySet().stream()
                .map(e -> new Exposure(e.getKey(), e.getKey(), e.getValue(), pct(e.getValue(), total)))
                .sorted(Comparator.comparing(Exposure::marketValue).reversed())
                .toList();
        List<Exposure> instrumentExposure = lines.stream().filter(l -> l.marketValue() != null)
                .map(l -> new Exposure(String.valueOf(l.instrumentId()), l.name(), l.marketValue(), l.pctOfTotal()))
                .sorted(Comparator.comparing(Exposure::marketValue).reversed())
                .toList();

        return new PortfolioValuation(money(marketValue), money(totalCost), money(marketValue.subtract(totalCost)),
                money(realized), lines, classExposure, instrumentExposure, priced, stale, unpriced);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal total) {
        return total.signum() == 0 ? BigDecimal.ZERO.setScale(2)
                : part.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
    }
}
