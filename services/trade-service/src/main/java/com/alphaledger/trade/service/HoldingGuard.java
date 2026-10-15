package com.alphaledger.trade.service;

import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.alphaledger.trade.repository.TradeRepository;

/**
 * Serialises holding checks for one portfolio and instrument. A PostgreSQL transaction-level advisory lock means
 * two concurrent SELLs (or a SELL and a cancelled BUY) cannot both pass a check that only one should pass.
 * Must be called inside a transaction; the lock is released at commit or rollback.
 */
@Component
public class HoldingGuard {

    private final JdbcTemplate jdbc;
    private final TradeRepository trades;

    public HoldingGuard(JdbcTemplate jdbc, TradeRepository trades) {
        this.jdbc = jdbc;
        this.trades = trades;
    }

    /** Locks the holding and returns its net quantity, ignoring {@code excludeTradeId} (use -1 for none). */
    public BigDecimal lockAndGet(long portfolioId, long instrumentId, long excludeTradeId) {
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", rs -> null,
                Math.toIntExact(portfolioId), Math.toIntExact(instrumentId));
        return trades.netHolding(portfolioId, instrumentId, excludeTradeId);
    }
}
