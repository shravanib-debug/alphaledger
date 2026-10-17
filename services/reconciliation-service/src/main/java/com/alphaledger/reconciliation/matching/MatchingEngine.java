package com.alphaledger.reconciliation.matching;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reconciliation matching engine (architecture.md §5.5). Records are matched on
 * {@code trade_ref + ISIN + trade_date}; for a matched pair the signed quantity (BUY +, SELL -) must be identical and the
 * price may differ by at most the tolerance. Hash-map matching: O(n) in the number of records.
 * <pre>
 * for each custodian record: no internal trade with its key -> MISSING_INTERNAL
 *                            quantity (or side) differs      -> QTY_MISMATCH
 *                            |price difference| > tolerance  -> PRICE_MISMATCH
 *                            otherwise                       -> matched
 * each internal trade with no custodian record               -> MISSING_EXTERNAL
 * </pre>
 */
public class MatchingEngine {

    public enum BreakType { MISSING_INTERNAL, MISSING_EXTERNAL, QTY_MISMATCH, PRICE_MISMATCH }

    public record ExternalRecord(int rowNumber, String tradeRef, String isin, LocalDate tradeDate, String side,
                                 BigDecimal quantity, BigDecimal price) {

        MatchKey key() {
            return new MatchKey(tradeRef, isin, tradeDate);
        }
    }

    public record InternalTrade(long tradeId, String tradeRef, Long portfolioId, String isin, LocalDate tradeDate,
                                String side, BigDecimal quantity, BigDecimal price) {

        MatchKey key() {
            return new MatchKey(tradeRef, isin, tradeDate);
        }
    }

    public record MatchKey(String tradeRef, String isin, LocalDate tradeDate) {
    }

    /** One break: {@code internal} is null for MISSING_INTERNAL, {@code external} for MISSING_EXTERNAL. */
    public record Finding(BreakType type, InternalTrade internal, ExternalRecord external) {

        public String tradeRef() {
            return internal != null ? internal.tradeRef() : external.tradeRef();
        }

        public String isin() {
            return internal != null ? internal.isin() : external.isin();
        }

        public LocalDate tradeDate() {
            return internal != null ? internal.tradeDate() : external.tradeDate();
        }
    }

    public record Result(int matched, List<Finding> findings) {

        public long count(BreakType type) {
            return findings.stream().filter(f -> f.type() == type).count();
        }
    }

    private final BigDecimal priceTolerance;

    public MatchingEngine(BigDecimal priceTolerance) {
        this.priceTolerance = priceTolerance;
    }

    public Result match(List<ExternalRecord> external, List<InternalTrade> internal) {
        Map<MatchKey, InternalTrade> unmatchedInternal = new LinkedHashMap<>();
        internal.forEach(t -> unmatchedInternal.put(t.key(), t));
        Map<MatchKey, Boolean> seen = new HashMap<>();
        List<Finding> findings = new ArrayList<>();
        int matched = 0;

        for (ExternalRecord e : external) {
            seen.put(e.key(), Boolean.TRUE);
            InternalTrade t = unmatchedInternal.remove(e.key());
            if (t == null) {
                findings.add(new Finding(BreakType.MISSING_INTERNAL, null, e));
            } else if (signed(t.side(), t.quantity()).compareTo(signed(e.side(), e.quantity())) != 0) {
                findings.add(new Finding(BreakType.QTY_MISMATCH, t, e));
            } else if (t.price().subtract(e.price()).abs().compareTo(priceTolerance) > 0) {
                findings.add(new Finding(BreakType.PRICE_MISMATCH, t, e));
            } else {
                matched++;
            }
        }
        unmatchedInternal.values().forEach(t -> findings.add(new Finding(BreakType.MISSING_EXTERNAL, t, null)));
        return new Result(matched, List.copyOf(findings));
    }

    private static BigDecimal signed(String side, BigDecimal quantity) {
        return "SELL".equals(side) ? quantity.negate() : quantity;
    }
}
