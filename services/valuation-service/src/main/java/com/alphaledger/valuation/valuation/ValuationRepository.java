package com.alphaledger.valuation.valuation;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.alphaledger.common.event.PositionEvent;
import com.alphaledger.valuation.valuation.ValuationCalculator.Exposure;
import com.alphaledger.valuation.valuation.ValuationCalculator.Holding;
import com.alphaledger.valuation.valuation.ValuationCalculator.PortfolioValuation;

@Repository
public class ValuationRepository {

    private final JdbcTemplate jdbc;

    public ValuationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Replaces the holding with the event's full state, unless a newer update was already applied. */
    public void upsertPosition(PositionEvent p) {
        jdbc.update("""
                INSERT INTO position_view (portfolio_id, instrument_id, isin, quantity, avg_cost, realized_pnl,
                                           last_trade_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (portfolio_id, instrument_id) DO UPDATE SET quantity = EXCLUDED.quantity,
                    avg_cost = EXCLUDED.avg_cost, realized_pnl = EXCLUDED.realized_pnl,
                    last_trade_id = EXCLUDED.last_trade_id, updated_at = EXCLUDED.updated_at
                WHERE position_view.updated_at <= EXCLUDED.updated_at
                """, p.portfolioId(), p.instrumentId(), p.isin(), p.quantity(), p.avgCost(), p.realizedPnl(),
                p.lastTradeId(), Timestamp.from(p.updatedAt()));
    }

    /** Holdings of a portfolio joined with instrument names and asset classes. */
    public List<Holding> holdings(long portfolioId) {
        return jdbc.query("""
                SELECT p.instrument_id, p.isin, COALESCE(r.name, p.isin) AS name,
                       COALESCE(r.asset_class, 'UNKNOWN') AS asset_class, p.quantity, p.avg_cost, p.realized_pnl
                FROM position_view p LEFT JOIN instrument_ref r ON r.instrument_id = p.instrument_id
                WHERE p.portfolio_id = ? ORDER BY p.instrument_id
                """, (rs, i) -> new Holding(rs.getLong("instrument_id"), rs.getString("isin"), rs.getString("name"),
                rs.getString("asset_class"), rs.getBigDecimal("quantity"), rs.getBigDecimal("avg_cost"),
                rs.getBigDecimal("realized_pnl")), portfolioId);
    }

    public List<Long> portfoliosWithPositions() {
        return jdbc.queryForList("SELECT DISTINCT portfolio_id FROM position_view ORDER BY portfolio_id", Long.class);
    }

    /** Writes the day's snapshot once; returns false if one already exists for the portfolio and date. */
    public boolean insertSnapshot(long portfolioId, LocalDate date, PortfolioValuation v, Instant now) {
        int rows = jdbc.update("""
                INSERT INTO valuation_snapshot (portfolio_id, as_of_date, market_value, total_cost, unrealized_pnl,
                    realized_pnl, nav, valuation_basis, priced_positions, stale_positions, unpriced_positions, created_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, 'MARKET_VALUE_ONLY', ?, ?, ?, ?)
                ON CONFLICT (portfolio_id, as_of_date) DO NOTHING
                """, portfolioId, Date.valueOf(date), v.marketValue(), v.totalCost(), v.unrealizedPnl(), v.realizedPnl(),
                v.pricedPositions(), v.stalePositions(), v.unpricedPositions(), Timestamp.from(now));
        if (rows == 0) {
            return false;
        }
        for (Exposure e : v.exposureByAssetClass()) {
            jdbc.update("""
                    INSERT INTO exposure_snapshot (portfolio_id, as_of_date, asset_class, market_value, pct_of_total)
                    VALUES (?, ?, ?, ?, ?)
                    """, portfolioId, Date.valueOf(date), e.key(), e.marketValue(), e.pctOfTotal());
        }
        return true;
    }

    public List<Map<String, Object>> history(long portfolioId, LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                SELECT as_of_date, market_value, total_cost, unrealized_pnl, realized_pnl, nav, valuation_basis,
                       priced_positions, stale_positions, unpriced_positions
                FROM valuation_snapshot WHERE portfolio_id = ? AND as_of_date BETWEEN ? AND ? ORDER BY as_of_date
                """, portfolioId, Date.valueOf(from), Date.valueOf(to));
    }

    /** The limit that applies: a portfolio-specific rule overrides the all-portfolio rule (portfolio_id NULL). */
    public Map<String, BigDecimal> limitsFor(long portfolioId) {
        Map<String, BigDecimal> limits = new java.util.HashMap<>();
        jdbc.query("SELECT asset_class, max_pct, portfolio_id FROM limit_rule WHERE portfolio_id IS NULL OR portfolio_id = ? "
                + "ORDER BY portfolio_id NULLS FIRST", rs -> {
            limits.put(rs.getString("asset_class"), rs.getBigDecimal("max_pct"));
        }, portfolioId);
        return limits;
    }

    public List<Map<String, Object>> limitRules() {
        return jdbc.queryForList("SELECT id, portfolio_id, asset_class, max_pct FROM limit_rule ORDER BY id");
    }

    public Map<String, Object> upsertLimitRule(Long portfolioId, String assetClass, BigDecimal maxPct) {
        return jdbc.queryForMap("""
                INSERT INTO limit_rule (portfolio_id, asset_class, max_pct) VALUES (?, ?, ?)
                ON CONFLICT (portfolio_id, asset_class) DO UPDATE SET max_pct = EXCLUDED.max_pct
                RETURNING id, portfolio_id, asset_class, max_pct
                """, portfolioId, assetClass, maxPct);
    }

    public int deleteLimitRule(long id) {
        return jdbc.update("DELETE FROM limit_rule WHERE id = ?", id);
    }

    /** Records a breach once per portfolio, asset class and day; returns true the first time. */
    public boolean recordBreach(long portfolioId, String assetClass, LocalDate date, BigDecimal exposurePct,
                                BigDecimal maxPct, BigDecimal marketValue, Instant now) {
        return jdbc.update("""
                INSERT INTO limit_breach (portfolio_id, asset_class, as_of_date, exposure_pct, max_pct, market_value,
                                          created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (portfolio_id, asset_class, as_of_date) DO NOTHING
                """, portfolioId, assetClass, Date.valueOf(date), exposurePct, maxPct, marketValue,
                Timestamp.from(now)) == 1;
    }

    public List<Map<String, Object>> breaches(Long portfolioId) {
        return portfolioId == null
                ? jdbc.queryForList("SELECT * FROM limit_breach ORDER BY as_of_date DESC, id DESC LIMIT 200")
                : jdbc.queryForList("SELECT * FROM limit_breach WHERE portfolio_id = ? ORDER BY as_of_date DESC, id DESC",
                portfolioId);
    }
}
