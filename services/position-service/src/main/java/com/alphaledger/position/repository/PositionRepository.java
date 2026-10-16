package com.alphaledger.position.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.alphaledger.common.event.TradeEvent;
import com.alphaledger.position.domain.PositionCalculator.AppliedTrade;
import com.alphaledger.position.domain.PositionCalculator.PositionState;
import com.alphaledger.position.domain.PositionCalculator.Skipped;

@Repository
public class PositionRepository {

    private final JdbcTemplate jdbc;

    public PositionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Records the trade; a cancel always wins, even if it arrives before the trade itself. */
    public void upsertAppliedTrade(TradeEvent t, boolean cancelled, Instant now) {
        jdbc.update("""
                INSERT INTO applied_trade (trade_id, trade_ref, portfolio_id, instrument_id, isin, side, quantity, price,
                                           trade_date, cancelled, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (trade_id) DO UPDATE SET cancelled = applied_trade.cancelled OR EXCLUDED.cancelled
                """, t.tradeId(), t.tradeRef(), t.portfolioId(), t.instrumentId(), t.isin(), t.side(), t.quantity(),
                t.price(), Date.valueOf(t.tradeDate()), cancelled, Timestamp.from(now));
    }

    public List<AppliedTrade> tradesFor(long portfolioId, long instrumentId) {
        return jdbc.query("""
                SELECT trade_id, side, quantity, price, trade_date, cancelled FROM applied_trade
                WHERE portfolio_id = ? AND instrument_id = ?
                """, (rs, i) -> new AppliedTrade(rs.getLong("trade_id"), rs.getString("side"),
                rs.getBigDecimal("quantity"), rs.getBigDecimal("price"), rs.getObject("trade_date", LocalDate.class),
                rs.getBoolean("cancelled")), portfolioId, instrumentId);
    }

    public void upsertPosition(long portfolioId, long instrumentId, String isin, PositionState state, Instant now) {
        jdbc.update("""
                INSERT INTO position (portfolio_id, instrument_id, isin, quantity, avg_cost, realized_pnl, last_trade_id,
                                      updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (portfolio_id, instrument_id) DO UPDATE SET quantity = EXCLUDED.quantity,
                    avg_cost = EXCLUDED.avg_cost, realized_pnl = EXCLUDED.realized_pnl,
                    last_trade_id = EXCLUDED.last_trade_id, updated_at = EXCLUDED.updated_at
                """, portfolioId, instrumentId, isin, state.quantity(), state.avgCost(), state.realizedPnl(),
                state.lastTradeId(), Timestamp.from(now));
    }

    public void recordSkips(long portfolioId, long instrumentId, List<Skipped> skipped, Instant now) {
        for (Skipped s : skipped) {
            jdbc.update("""
                    INSERT INTO position_exception (trade_id, portfolio_id, instrument_id, reason, created_at)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT (trade_id, reason) DO NOTHING
                    """, s.tradeId(), portfolioId, instrumentId, s.reason(), Timestamp.from(now));
        }
    }

    public List<PositionRow> positions(long portfolioId, boolean includeClosed) {
        return jdbc.query("""
                SELECT portfolio_id, instrument_id, isin, quantity, avg_cost, realized_pnl, last_trade_id, updated_at
                FROM position WHERE portfolio_id = ? AND (? OR quantity <> 0) ORDER BY instrument_id
                """, (rs, i) -> new PositionRow(rs.getLong("portfolio_id"), rs.getLong("instrument_id"),
                rs.getString("isin"), rs.getBigDecimal("quantity"), rs.getBigDecimal("avg_cost"),
                rs.getBigDecimal("realized_pnl"), rs.getLong("last_trade_id"),
                rs.getTimestamp("updated_at").toInstant()), portfolioId, includeClosed);
    }

    /** Copies every position into position_snapshot for the date; re-running the same date overwrites it. */
    public int snapshot(LocalDate date) {
        return jdbc.update("""
                INSERT INTO position_snapshot (portfolio_id, instrument_id, isin, snapshot_date, quantity, avg_cost,
                                               realized_pnl)
                SELECT portfolio_id, instrument_id, isin, ?, quantity, avg_cost, realized_pnl FROM position
                ON CONFLICT (portfolio_id, instrument_id, snapshot_date) DO UPDATE SET quantity = EXCLUDED.quantity,
                    avg_cost = EXCLUDED.avg_cost, realized_pnl = EXCLUDED.realized_pnl
                """, Date.valueOf(date));
    }

    public List<PositionRow> snapshots(long portfolioId, LocalDate date) {
        return jdbc.query("""
                SELECT portfolio_id, instrument_id, isin, quantity, avg_cost, realized_pnl FROM position_snapshot
                WHERE portfolio_id = ? AND snapshot_date = ? ORDER BY instrument_id
                """, (rs, i) -> new PositionRow(rs.getLong("portfolio_id"), rs.getLong("instrument_id"),
                rs.getString("isin"), rs.getBigDecimal("quantity"), rs.getBigDecimal("avg_cost"),
                rs.getBigDecimal("realized_pnl"), 0, null), portfolioId, Date.valueOf(date));
    }

    public List<java.util.Map<String, Object>> exceptions(long portfolioId) {
        return jdbc.queryForList("SELECT trade_id, instrument_id, reason, created_at FROM position_exception "
                + "WHERE portfolio_id = ? ORDER BY id", portfolioId);
    }

    public record PositionRow(long portfolioId, long instrumentId, String isin, BigDecimal quantity, BigDecimal avgCost,
                              BigDecimal realizedPnl, long lastTradeId, Instant updatedAt) {
    }
}
