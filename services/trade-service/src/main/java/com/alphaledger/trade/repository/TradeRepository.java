package com.alphaledger.trade.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.alphaledger.trade.domain.Trade;
import com.alphaledger.trade.domain.TradeSide;
import com.alphaledger.trade.domain.TradeStatus;

public interface TradeRepository extends JpaRepository<Trade, Long>, JpaSpecificationExecutor<Trade> {

    Optional<Trade> findByTradeRef(String tradeRef);

    /** Net quantity held (BUY minus SELL over VALIDATED and SETTLED trades), optionally ignoring one trade. */
    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN side = 'BUY' THEN quantity ELSE -quantity END), 0)
            FROM trade
            WHERE portfolio_id = :portfolioId AND instrument_id = :instrumentId
              AND status IN ('VALIDATED', 'SETTLED') AND id <> :excludeTradeId
            """, nativeQuery = true)
    BigDecimal netHolding(@Param("portfolioId") long portfolioId, @Param("instrumentId") long instrumentId,
                          @Param("excludeTradeId") long excludeTradeId);

    /** Locks a batch of trades due for settlement; rows locked by another instance are skipped. */
    @Query(value = """
            SELECT id FROM trade
            WHERE status = 'VALIDATED' AND settle_date <= :businessDate
            ORDER BY id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockDueForSettlement(@Param("businessDate") LocalDate businessDate,
                                    @Param("batchSize") int batchSize);

    /** Blotter filters (FR-TR-5); null arguments are ignored. Trade dates are inclusive. */
    static Specification<Trade> blotter(Long portfolioId, TradeStatus status, String isin, TradeSide side,
                                        LocalDate from, LocalDate to) {
        Specification<Trade> spec = Specification.unrestricted();
        if (portfolioId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("portfolioId"), portfolioId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (isin != null && !isin.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("isin"), isin.trim().toUpperCase(Locale.ROOT)));
        }
        if (side != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("side"), side));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("tradeDate"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("tradeDate"), to));
        }
        return spec;
    }
}
