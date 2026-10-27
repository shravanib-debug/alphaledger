package com.alphaledger.analytics.etl;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.alphaledger.analytics.config.AnalyticsProperties;

/**
 * Transform and load: staging -> core star schema, set-based SQL in one transaction. Every statement can run again
 * with the same result (idempotent):
 * <ul>
 * <li>dimensions: upsert by natural key (SCD type 1);</li>
 * <li>daily facts (positions, valuations): delete the days in scope, then insert them;</li>
 * <li>trades and breaks (one row per entity, latest state): upsert by ID;</li>
 * <li>a fact whose portfolio or instrument is not (yet) in its dimension points at the Unknown member (-1), so no
 * row is dropped; the data-quality step reports how many.</li>
 * </ul>
 */
@Component
public class Loader {

    private final JdbcTemplate jdbc;
    private final String zone;

    public Loader(JdbcTemplate jdbc, AnalyticsProperties properties) {
        this.jdbc = jdbc;
        this.zone = properties.zone();
    }

    /** Loads the staged run and returns rows written per core table. */
    @Transactional
    public Map<String, Integer> load(LocalDate runDate, boolean fullRefresh) {
        Date d = Date.valueOf(runDate);
        Map<String, Integer> rows = new LinkedHashMap<>();

        rows.put("dim_portfolio", jdbc.update("""
                INSERT INTO core.dim_portfolio (portfolio_id, name, base_currency, status, updated_at)
                SELECT portfolio_id, name, base_currency, status, now() FROM staging.stg_portfolio
                ON CONFLICT (portfolio_id) DO UPDATE SET name = EXCLUDED.name, base_currency = EXCLUDED.base_currency,
                    status = EXCLUDED.status, updated_at = EXCLUDED.updated_at
                """));
        rows.put("dim_instrument", jdbc.update("""
                INSERT INTO core.dim_instrument (instrument_id, isin, symbol, exchange, name, instrument_type,
                                                 asset_class, currency, is_synthetic, updated_at)
                SELECT instrument_id, isin, symbol, exchange, name, instrument_type, asset_class, currency,
                       is_synthetic, now() FROM staging.stg_instrument
                ON CONFLICT (instrument_id) DO UPDATE SET isin = EXCLUDED.isin, symbol = EXCLUDED.symbol,
                    exchange = EXCLUDED.exchange, name = EXCLUDED.name, instrument_type = EXCLUDED.instrument_type,
                    asset_class = EXCLUDED.asset_class, currency = EXCLUDED.currency,
                    is_synthetic = EXCLUDED.is_synthetic, updated_at = EXCLUDED.updated_at
                """));

        rows.put("fact_trade", jdbc.update("""
                INSERT INTO core.fact_trade (trade_id, date_key, settle_date_key, portfolio_key, instrument_key,
                                             trade_ref, side, quantity, price, notional, status, loaded_at)
                SELECT t.trade_id, to_char(t.trade_date, 'YYYYMMDD')::INT, to_char(t.settle_date, 'YYYYMMDD')::INT,
                       coalesce(p.portfolio_key, -1), coalesce(i.instrument_key, -1), t.trade_ref, t.side, t.quantity,
                       t.price, round(t.quantity * t.price, 2), t.status, now()
                FROM staging.stg_trade t
                LEFT JOIN core.dim_portfolio p ON p.portfolio_id = t.portfolio_id
                LEFT JOIN core.dim_instrument i ON i.instrument_id = t.instrument_id
                ON CONFLICT (trade_id) DO UPDATE SET date_key = EXCLUDED.date_key,
                    settle_date_key = EXCLUDED.settle_date_key, portfolio_key = EXCLUDED.portfolio_key,
                    instrument_key = EXCLUDED.instrument_key, trade_ref = EXCLUDED.trade_ref, side = EXCLUDED.side,
                    quantity = EXCLUDED.quantity, price = EXCLUDED.price, notional = EXCLUDED.notional,
                    status = EXCLUDED.status, loaded_at = EXCLUDED.loaded_at
                """));

        String days = fullRefresh ? "date_key <= ?" : "date_key = ?";
        int dayKey = Integer.parseInt(runDate.toString().replace("-", ""));
        jdbc.update("DELETE FROM core.fact_position_daily WHERE " + days, dayKey);
        rows.put("fact_position_daily", jdbc.update("""
                INSERT INTO core.fact_position_daily (date_key, portfolio_key, instrument_key, quantity, avg_cost, cost,
                    price, price_date, market_value, unrealized_pnl, realized_pnl, loaded_at)
                SELECT to_char(s.snapshot_date, 'YYYYMMDD')::INT, coalesce(p.portfolio_key, -1),
                       coalesce(i.instrument_key, -1), s.quantity, s.avg_cost, round(s.quantity * s.avg_cost, 2),
                       pr.price, pr.data_date, round(s.quantity * pr.price, 2),
                       round(s.quantity * pr.price, 2) - round(s.quantity * s.avg_cost, 2), round(s.realized_pnl, 2), now()
                FROM staging.stg_position_snapshot s
                LEFT JOIN core.dim_portfolio p ON p.portfolio_id = s.portfolio_id
                LEFT JOIN core.dim_instrument i ON i.instrument_id = s.instrument_id
                LEFT JOIN LATERAL (SELECT price, data_date FROM staging.stg_price x
                                   WHERE x.instrument_id = s.instrument_id AND x.data_date <= s.snapshot_date
                                   ORDER BY x.data_date DESC LIMIT 1) pr ON TRUE
                """));

        jdbc.update("DELETE FROM core.fact_valuation_daily WHERE " + days, dayKey);
        rows.put("fact_valuation_daily", jdbc.update("""
                INSERT INTO core.fact_valuation_daily (date_key, portfolio_key, market_value, cash_balance,
                    total_liabilities, nav, valuation_basis, total_cost, realized_pnl, unrealized_pnl, loaded_at)
                SELECT to_char(v.as_of_date, 'YYYYMMDD')::INT, coalesce(p.portfolio_key, -1), v.market_value,
                       v.cash_balance, v.total_liabilities, v.nav, v.valuation_basis, v.total_cost, v.realized_pnl,
                       v.unrealized_pnl, now()
                FROM staging.stg_valuation_snapshot v
                LEFT JOIN core.dim_portfolio p ON p.portfolio_id = v.portfolio_id
                """));

        String raised = "(b.created_at AT TIME ZONE '" + zone + "')::date";
        String resolved = "(b.resolved_at AT TIME ZONE '" + zone + "')::date";
        rows.put("fact_recon_break", jdbc.update("""
                INSERT INTO core.fact_recon_break (break_id, date_key, resolved_date_key, portfolio_key, run_id,
                    break_type, status, resolution_type, trade_ref, isin, age_days, loaded_at)
                SELECT b.break_id, to_char(%1$s, 'YYYYMMDD')::INT, to_char(%2$s, 'YYYYMMDD')::INT,
                       coalesce(p.portfolio_key, -1), b.run_id, b.break_type, b.status, b.resolution_type, b.trade_ref,
                       b.isin, greatest(0, coalesce(%2$s, ?) - %1$s), now()
                FROM staging.stg_recon_break b
                LEFT JOIN core.dim_portfolio p ON p.portfolio_id = b.portfolio_id
                ON CONFLICT (break_id) DO UPDATE SET date_key = EXCLUDED.date_key,
                    resolved_date_key = EXCLUDED.resolved_date_key, portfolio_key = EXCLUDED.portfolio_key,
                    status = EXCLUDED.status, resolution_type = EXCLUDED.resolution_type,
                    age_days = EXCLUDED.age_days, loaded_at = EXCLUDED.loaded_at
                """.formatted(raised, resolved), d));
        // Breaks still open age every day even when nothing about them changed.
        jdbc.update("""
                UPDATE core.fact_recon_break SET age_days = greatest(0, ? - to_date(date_key::TEXT, 'YYYYMMDD'))
                WHERE status <> 'RESOLVED'
                """, d);
        return rows;
    }
}
