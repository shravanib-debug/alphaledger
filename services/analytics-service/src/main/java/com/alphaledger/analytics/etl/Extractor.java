package com.alphaledger.analytics.etl;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.alphaledger.analytics.config.AnalyticsProperties;
import com.alphaledger.analytics.config.AnalyticsProperties.Source;
import com.alphaledger.analytics.config.SourceDatabases;

/**
 * Extract: copies each source query's rows unchanged into its staging table (truncated first), in batches.
 * <ul>
 * <li>Dimensions (portfolios, instruments) are extracted in full every run.</li>
 * <li>Incremental runs take one day: trades and breaks raised or changed that day (IST), and that day's position and
 * valuation snapshots. A full refresh takes everything on or before the run date.</li>
 * <li>Prices: one per instrument and data date, on or before the run date (real sources preferred over synthetic
 * marks), so positions can be valued as of any day in scope.</li>
 * </ul>
 */
@Component
public class Extractor {

    private static final int BATCH = 1000;

    private final SourceDatabases sources;
    private final JdbcTemplate warehouse;
    private final String zone;

    public Extractor(SourceDatabases sources, JdbcTemplate warehouse, AnalyticsProperties properties) {
        this.sources = sources;
        this.warehouse = warehouse;
        this.zone = properties.zone();
    }

    /** Extracts everything for the run and returns rows copied per staging table. */
    public Map<String, Integer> extract(LocalDate runDate, boolean fullRefresh) {
        Date d = Date.valueOf(runDate);
        String scope = fullRefresh ? "<= ?" : "= ?";
        Map<String, Integer> rows = new LinkedHashMap<>();

        rows.put("stg_portfolio", copy(Source.PORTFOLIO,
                "SELECT id, name, base_currency, status FROM portfolio",
                "staging.stg_portfolio", 4));
        rows.put("stg_instrument", copy(Source.PORTFOLIO,
                "SELECT id, isin, symbol, exchange, name, asset_class, instrument_type, currency, is_synthetic "
                        + "FROM instrument",
                "staging.stg_instrument", 9));
        rows.put("stg_trade", fullRefresh
                ? copy(Source.TRADE, tradeSql("trade_date <= ?"), "staging.stg_trade", 12, d)
                : copy(Source.TRADE, tradeSql("trade_date = ? OR (updated_at AT TIME ZONE '" + zone + "')::date = ?"),
                "staging.stg_trade", 12, d, d));
        rows.put("stg_position_snapshot", copy(Source.POSITION,
                "SELECT portfolio_id, instrument_id, snapshot_date, quantity, avg_cost, realized_pnl "
                        + "FROM position_snapshot WHERE snapshot_date " + scope,
                "staging.stg_position_snapshot", 6, d));
        rows.put("stg_price", copy(Source.VALUATION, """
                SELECT DISTINCT ON (instrument_id, data_date) instrument_id, data_date, price, source
                FROM price WHERE data_date <= ?
                ORDER BY instrument_id, data_date, (source = 'SYNTHETIC'), as_of_timestamp DESC
                """, "staging.stg_price", 4, d));
        rows.put("stg_valuation_snapshot", copy(Source.VALUATION,
                "SELECT portfolio_id, as_of_date, market_value, total_cost, unrealized_pnl, realized_pnl, cash_balance, "
                        + "total_liabilities, nav, valuation_basis FROM valuation_snapshot WHERE as_of_date " + scope,
                "staging.stg_valuation_snapshot", 10, d));
        String raised = "(created_at AT TIME ZONE '" + zone + "')::date";
        rows.put("stg_recon_break", fullRefresh
                ? copy(Source.RECON, breakSql(raised + " <= ?"), "staging.stg_recon_break", 10, d)
                : copy(Source.RECON, breakSql(raised + " = ? OR (updated_at AT TIME ZONE '" + zone + "')::date = ?"),
                "staging.stg_recon_break", 10, d, d));
        return rows;
    }

    private static String tradeSql(String where) {
        return "SELECT id, trade_ref, portfolio_id, instrument_id, isin, side, quantity, price, trade_date, settle_date, "
                + "status, updated_at FROM trade WHERE " + where;
    }

    private static String breakSql(String where) {
        return "SELECT id, run_id, break_type, trade_ref, isin, portfolio_id, status, resolution_type, created_at, "
                + "resolved_at FROM recon_break WHERE " + where;
    }

    private int copy(Source source, String select, String stagingTable, int columns, Object... args) {
        warehouse.execute("TRUNCATE " + stagingTable);
        String insert = "INSERT INTO " + stagingTable + " VALUES (" + "?, ".repeat(columns - 1) + "?)";
        List<Object[]> batch = new ArrayList<>(BATCH);
        int[] total = {0};
        sources.jdbc(source).query(select, rs -> {
            Object[] row = new Object[columns];
            for (int c = 0; c < columns; c++) {
                row[c] = rs.getObject(c + 1);
            }
            batch.add(row);
            if (batch.size() == BATCH) {
                warehouse.batchUpdate(insert, batch);
                total[0] += batch.size();
                batch.clear();
            }
        }, args);
        if (!batch.isEmpty()) {
            warehouse.batchUpdate(insert, batch);
            total[0] += batch.size();
        }
        return total[0];
    }
}
