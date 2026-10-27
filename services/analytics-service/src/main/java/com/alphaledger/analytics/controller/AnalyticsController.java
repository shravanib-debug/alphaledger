package com.alphaledger.analytics.controller;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alphaledger.analytics.etl.EtlService;
import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.error.ValidationException;

/**
 * Mart views and ETL runs. Any signed-in role can read; Admin starts runs. Dates are returned as ISO text
 * ({@code yyyy-MM-dd}), not timestamps.
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final JdbcTemplate jdbc;
    private final EtlService etl;
    private final MarketClock clock;

    public AnalyticsController(JdbcTemplate jdbc, EtlService etl, MarketClock clock) {
        this.jdbc = jdbc;
        this.etl = etl;
        this.clock = clock;
    }

    /** AUM (market value) by asset class for a day; default: the latest day loaded. */
    @GetMapping("/aum-by-asset-class")
    public Map<String, Object> aumByAssetClass(@RequestParam(required = false)
                                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date != null ? date
                : jdbc.queryForObject("SELECT max(date) FROM mart.vw_aum_by_asset_class", LocalDate.class);
        List<Map<String, Object>> rows = day == null ? List.of() : jdbc.queryForList("""
                SELECT asset_class, market_value, positions, pct_of_total FROM mart.vw_aum_by_asset_class
                WHERE date = ? ORDER BY market_value DESC
                """, Date.valueOf(day));
        return Map.of("date", day == null ? "" : day.toString(), "rows", rows);
    }

    @GetMapping("/daily-pnl")
    public List<Map<String, Object>> dailyPnl(@RequestParam(required = false) Long portfolioId,
                                              @RequestParam(required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? clock.today() : to;
        LocalDate start = range(from, end);
        return jdbc.queryForList("""
                SELECT date::TEXT AS date, portfolio_id, portfolio_name, market_value, nav, realized_pnl, unrealized_pnl, total_pnl,
                       daily_pnl
                FROM mart.vw_daily_pnl WHERE date BETWEEN ? AND ? AND (?::BIGINT IS NULL OR portfolio_id = ?)
                ORDER BY date, portfolio_id
                """, Date.valueOf(start), Date.valueOf(end), portfolioId, portfolioId);
    }

    @GetMapping("/break-trend")
    public List<Map<String, Object>> breakTrend(@RequestParam(required = false)
                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam(required = false)
                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? clock.today() : to;
        LocalDate start = range(from, end);
        return jdbc.queryForList("""
                SELECT date::TEXT AS date, break_type, raised, resolved, open_at_end_of_day FROM mart.vw_break_trend
                WHERE date BETWEEN ? AND ? ORDER BY date, break_type
                """, Date.valueOf(start), Date.valueOf(end));
    }

    @GetMapping("/break-ageing")
    public List<Map<String, Object>> breakAgeing() {
        return jdbc.queryForList("SELECT break_type, age_bucket, open_breaks, oldest_days FROM mart.vw_break_ageing "
                + "ORDER BY bucket_order, break_type");
    }

    @GetMapping("/etl-runs")
    public List<Map<String, Object>> etlRuns() {
        return jdbc.queryForList("SELECT id, run_date::TEXT AS run_date, full_refresh, status, rows_extracted::text AS rows_extracted, "
                + "rows_loaded::text AS rows_loaded, message, started_at, finished_at FROM core.etl_run "
                + "ORDER BY id DESC LIMIT 50");
    }

    /** Data-quality results of a run; default: the latest run. */
    @GetMapping("/dq-results")
    public List<Map<String, Object>> dqResults(@RequestParam(required = false) Long runId) {
        return jdbc.queryForList("""
                SELECT etl_run_id, check_name, category, severity, status, expected, actual, details, run_at
                FROM core.dq_check_result
                WHERE etl_run_id = coalesce(?::BIGINT, (SELECT max(id) FROM core.etl_run))
                ORDER BY status DESC, severity, check_name
                """, runId);
    }

    /** Runs the ETL now for {@code date} (default yesterday); {@code fullRefresh} reloads everything up to it. */
    @PostMapping("/etl-runs")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> runEtl(@RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                      @RequestParam(defaultValue = "false") boolean fullRefresh) {
        return etl.run(date == null ? clock.today().minusDays(1) : date, fullRefresh);
    }

    @GetMapping("/etl-runs/{id}")
    public Map<String, Object> etlRun(@PathVariable long id) {
        return etl.run(id);
    }

    private static LocalDate range(LocalDate from, LocalDate end) {
        LocalDate start = from == null ? end.minusDays(30) : from;
        if (start.isAfter(end)) {
            throw new ValidationException("from: must be on or before 'to'");
        }
        return start;
    }
}
