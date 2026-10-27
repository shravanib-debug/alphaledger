package com.alphaledger.analytics.dq;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.alphaledger.common.calendar.MarketClock;

/**
 * Data-quality checks run after every load (architecture.md §5.6). Each result is stored in
 * {@code core.dq_check_result}; the ETL job fails when any CRITICAL check fails, so a bad load is visible at once.
 * <ul>
 * <li>Row counts: every staged source row reached its fact table.</li>
 * <li>Not null and uniqueness on keys and grain.</li>
 * <li>Referential integrity: every fact key exists in its dimension (there are no foreign keys to enforce it).</li>
 * <li>Sanity: market value and quantity not negative, prices positive, nothing dated after today. A negative NAV is
 * legitimate (liabilities above assets) and only reported.</li>
 * <li>Freshness: a weekday without a valuation snapshot is a warning.</li>
 * </ul>
 */
@Component
public class DataQualityChecker {

    public record CheckResult(String name, String category, String severity, boolean passed, String expected,
                              String actual, String details) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final MarketClock marketClock;
    private final TransactionTemplate separateTransaction;

    public DataQualityChecker(JdbcTemplate jdbc, Clock clock, MarketClock marketClock,
                              PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.marketClock = marketClock;
        this.separateTransaction = new TransactionTemplate(transactionManager);
        this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Runs every check for the run, stores the results and returns them. */
    public List<CheckResult> run(long etlRunId, LocalDate runDate, boolean fullRefresh) {
        int dayKey = Integer.parseInt(runDate.toString().replace("-", ""));
        String days = fullRefresh ? "date_key <= " + dayKey : "date_key = " + dayKey;
        List<CheckResult> results = new ArrayList<>();

        // Row counts: staged source rows vs rows loaded into the fact.
        results.add(equal("rowcount_trade", "ROW_COUNT",
                "SELECT count(*) FROM staging.stg_trade",
                "SELECT count(*) FROM core.fact_trade f JOIN staging.stg_trade s ON s.trade_id = f.trade_id",
                "staged trades loaded into fact_trade"));
        results.add(equal("rowcount_position_daily", "ROW_COUNT",
                "SELECT count(*) FROM staging.stg_position_snapshot",
                "SELECT count(*) FROM core.fact_position_daily WHERE " + days,
                "position snapshots loaded into fact_position_daily"));
        results.add(equal("rowcount_valuation_daily", "ROW_COUNT",
                "SELECT count(*) FROM staging.stg_valuation_snapshot",
                "SELECT count(*) FROM core.fact_valuation_daily WHERE " + days,
                "valuation snapshots loaded into fact_valuation_daily"));
        results.add(equal("rowcount_recon_break", "ROW_COUNT",
                "SELECT count(*) FROM staging.stg_recon_break",
                "SELECT count(*) FROM core.fact_recon_break f JOIN staging.stg_recon_break s ON s.break_id = f.break_id",
                "staged breaks loaded into fact_recon_break"));

        // Not null: a FULL_NAV valuation must carry its NAV.
        results.add(zero("not_null_nav", "NOT_NULL", "CRITICAL",
                "SELECT count(*) FROM core.fact_valuation_daily WHERE valuation_basis = 'FULL_NAV' AND nav IS NULL",
                "FULL_NAV valuations without a NAV"));
        results.add(zero("not_null_position_price", "NOT_NULL", "WARNING",
                "SELECT count(*) FROM core.fact_position_daily WHERE quantity <> 0 AND market_value IS NULL AND " + days,
                "open positions without any price on or before the day (left out of AUM)"));

        // Uniqueness on grain and business keys.
        results.add(zero("unique_stg_trade_id", "UNIQUENESS", "CRITICAL",
                "SELECT count(*) FROM (SELECT trade_id FROM staging.stg_trade GROUP BY trade_id HAVING count(*) > 1) x",
                "trade IDs extracted more than once"));
        results.add(zero("unique_trade_ref", "UNIQUENESS", "CRITICAL",
                "SELECT count(*) FROM (SELECT trade_ref FROM core.fact_trade GROUP BY trade_ref HAVING count(*) > 1) x",
                "trade references on more than one trade"));
        results.add(zero("unique_dim_instrument_isin", "UNIQUENESS", "CRITICAL",
                "SELECT count(*) FROM (SELECT isin FROM core.dim_instrument GROUP BY isin HAVING count(*) > 1) x",
                "ISINs on more than one instrument"));

        // Referential integrity.
        results.add(orphans("ref_trade_portfolio", "core.fact_trade", "portfolio_key", "core.dim_portfolio"));
        results.add(orphans("ref_trade_instrument", "core.fact_trade", "instrument_key", "core.dim_instrument"));
        results.add(orphans("ref_trade_date", "core.fact_trade", "date_key", "core.dim_date"));
        results.add(orphans("ref_position_portfolio", "core.fact_position_daily", "portfolio_key", "core.dim_portfolio"));
        results.add(orphans("ref_position_instrument", "core.fact_position_daily", "instrument_key",
                "core.dim_instrument"));
        results.add(orphans("ref_valuation_portfolio", "core.fact_valuation_daily", "portfolio_key",
                "core.dim_portfolio"));
        results.add(orphans("ref_break_date", "core.fact_recon_break", "date_key", "core.dim_date"));
        results.add(zero("ref_unknown_members", "REFERENTIAL", "WARNING", """
                SELECT (SELECT count(*) FROM core.fact_trade WHERE instrument_key = -1 OR portfolio_key = -1)
                     + (SELECT count(*) FROM core.fact_position_daily WHERE instrument_key = -1 OR portfolio_key = -1)
                     + (SELECT count(*) FROM core.fact_valuation_daily WHERE portfolio_key = -1)
                """, "facts pointing at the Unknown member (e.g. a rejected trade with an unknown ISIN)"));

        // Sanity.
        results.add(zero("sanity_position_market_value", "SANITY", "CRITICAL",
                "SELECT count(*) FROM core.fact_position_daily WHERE market_value < 0 OR quantity < 0",
                "positions with negative market value or quantity"));
        results.add(zero("sanity_valuation_market_value", "SANITY", "CRITICAL",
                "SELECT count(*) FROM core.fact_valuation_daily WHERE market_value < 0",
                "valuations with negative market value"));
        results.add(zero("sanity_trade_values", "SANITY", "CRITICAL",
                "SELECT count(*) FROM core.fact_trade WHERE quantity <= 0 OR price <= 0",
                "trades with zero or negative quantity or price"));
        int todayKey = Integer.parseInt(marketClock.today().toString().replace("-", ""));
        results.add(zero("sanity_no_future_dates", "SANITY", "CRITICAL",
                "SELECT (SELECT count(*) FROM core.fact_trade WHERE date_key > " + todayKey + ")"
                        + " + (SELECT count(*) FROM core.fact_position_daily WHERE date_key > " + todayKey + ")"
                        + " + (SELECT count(*) FROM core.fact_valuation_daily WHERE date_key > " + todayKey + ")",
                "rows dated after today (market time zone)"));
        long negativeNav = count("SELECT count(*) FROM core.fact_valuation_daily WHERE nav < 0 AND " + days);
        results.add(new CheckResult("info_negative_nav", "SANITY", "INFO", true, null, String.valueOf(negativeNav),
                "valuations with a negative NAV (allowed: liabilities above assets)"));

        // Freshness.
        boolean weekday = runDate.getDayOfWeek() != DayOfWeek.SATURDAY && runDate.getDayOfWeek() != DayOfWeek.SUNDAY;
        long snapshots = count("SELECT count(*) FROM core.fact_valuation_daily WHERE date_key = " + dayKey);
        results.add(new CheckResult("fresh_valuation_snapshot", "FRESHNESS", "WARNING", !weekday || snapshots > 0,
                weekday ? "> 0" : "n/a (weekend)", String.valueOf(snapshots),
                "valuation snapshots for the run date (none on an NSE holiday is expected)"));

        store(etlRunId, results);
        return results;
    }

    private CheckResult equal(String name, String category, String expectedSql, String actualSql, String details) {
        long expected = count(expectedSql);
        long actual = count(actualSql);
        return new CheckResult(name, category, "CRITICAL", expected == actual, String.valueOf(expected),
                String.valueOf(actual), details);
    }

    private CheckResult zero(String name, String category, String severity, String sql, String details) {
        long actual = count(sql);
        return new CheckResult(name, category, severity, actual == 0, "0", String.valueOf(actual), details);
    }

    private CheckResult orphans(String name, String fact, String key, String dimension) {
        return zero(name, "REFERENTIAL", "CRITICAL", "SELECT count(*) FROM " + fact + " f WHERE NOT EXISTS (SELECT 1 FROM "
                + dimension + " d WHERE d." + key + " = f." + key + ")", fact + "." + key + " not in " + dimension);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    /** Stored in its own transaction, so the results survive when a critical failure fails the step. */
    private void store(long etlRunId, List<CheckResult> results) {
        Timestamp now = Timestamp.from(clock.instant());
        separateTransaction.executeWithoutResult(status -> jdbc.batchUpdate("""
                INSERT INTO core.dq_check_result (etl_run_id, check_name, category, severity, status, expected, actual,
                                                  details, run_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, results.stream().map(r -> new Object[] {etlRunId, r.name(), r.category(), r.severity(),
                r.passed() ? "PASSED" : "FAILED", r.expected(), r.actual(), r.details(), now}).toList()));
    }
}
