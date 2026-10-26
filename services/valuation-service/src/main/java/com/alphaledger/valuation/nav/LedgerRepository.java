package com.alphaledger.valuation.nav;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Cash ledger, derived cash balance and liabilities (V5). */
@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends a ledger entry and moves the current balance by the same amount. Returns false (and changes nothing)
     * if a trade's settlement was already recorded.
     */
    public boolean appendCash(long portfolioId, String type, BigDecimal amount, String referenceId, String description,
                              LocalDate effectiveDate, Long createdBy, Instant now) {
        int rows = jdbc.update("""
                INSERT INTO cash_transaction (portfolio_id, type, amount, reference_id, description, effective_date,
                                              occurred_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (type, reference_id) WHERE type IN ('TRADE_BUY', 'TRADE_SELL') DO NOTHING
                """, portfolioId, type, amount, referenceId, description, Date.valueOf(effectiveDate), Timestamp.from(now),
                createdBy);
        if (rows == 0) {
            return false;
        }
        jdbc.update("""
                INSERT INTO cash_balance (portfolio_id, currency, balance, updated_at) VALUES (?, 'INR', ?, ?)
                ON CONFLICT (portfolio_id, currency)
                    DO UPDATE SET balance = cash_balance.balance + EXCLUDED.balance, updated_at = EXCLUDED.updated_at
                """, portfolioId, amount, Timestamp.from(now));
        return true;
    }

    /**
     * Current balance (all entries, including any effective in the future). {@code lock} holds the row so a
     * withdrawal check and its entry see the same balance.
     */
    public BigDecimal balance(long portfolioId, boolean lock) {
        List<BigDecimal> rows = jdbc.queryForList(
                "SELECT balance FROM cash_balance WHERE portfolio_id = ? AND currency = 'INR'" + (lock ? " FOR UPDATE" : ""),
                BigDecimal.class, portfolioId);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
    }

    /** Cash on a date, rebuilt from the ledger (entries effective on or before it). */
    public BigDecimal cashAsOf(long portfolioId, LocalDate date) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM cash_transaction WHERE portfolio_id = ? AND effective_date <= ?
                """, BigDecimal.class, portfolioId, Date.valueOf(date));
    }

    /** Liabilities outstanding on a date: accrued on or before it and not yet paid on it. */
    public BigDecimal liabilitiesAsOf(long portfolioId, LocalDate date) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM liability
                WHERE portfolio_id = ? AND accrual_date <= ? AND (paid_date IS NULL OR paid_date > ?)
                """, BigDecimal.class, portfolioId, Date.valueOf(date), Date.valueOf(date));
    }

    public List<Map<String, Object>> cashTransactions(long portfolioId) {
        return jdbc.queryForList("""
                SELECT id, type, amount, currency, reference_id, description, effective_date, occurred_at, created_by
                FROM cash_transaction WHERE portfolio_id = ? ORDER BY effective_date DESC, id DESC
                """, portfolioId);
    }

    /** Portfolios with any cash entry or liability, so a cash-only portfolio is still valued. */
    public List<Long> portfoliosWithLedger() {
        return jdbc.queryForList("""
                SELECT portfolio_id FROM cash_transaction UNION SELECT portfolio_id FROM liability ORDER BY 1
                """, Long.class);
    }

    public List<Map<String, Object>> liabilities(long portfolioId, String status) {
        return status == null
                ? jdbc.queryForList("SELECT * FROM liability WHERE portfolio_id = ? ORDER BY accrual_date DESC, id DESC",
                portfolioId)
                : jdbc.queryForList("SELECT * FROM liability WHERE portfolio_id = ? AND status = ? "
                + "ORDER BY accrual_date DESC, id DESC", portfolioId, status);
    }

    public Optional<Map<String, Object>> liabilityForUpdate(long portfolioId, long id) {
        return jdbc.queryForList("SELECT * FROM liability WHERE portfolio_id = ? AND id = ? FOR UPDATE", portfolioId, id)
                .stream().findFirst();
    }

    public Map<String, Object> insertLiability(long portfolioId, String type, String description, BigDecimal amount,
                                               LocalDate accrualDate, Instant now) {
        return jdbc.queryForMap("""
                INSERT INTO liability (portfolio_id, type, description, amount, accrual_date, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING *
                """, portfolioId, type, description, amount, Date.valueOf(accrualDate), Timestamp.from(now),
                Timestamp.from(now));
    }

    /** One management-fee accrual per portfolio and day; returns false if the day was already accrued. */
    public boolean insertFeeAccrual(long portfolioId, BigDecimal amount, LocalDate date, String description,
                                    Instant now) {
        return jdbc.update("""
                INSERT INTO liability (portfolio_id, type, description, amount, accrual_date, created_at, updated_at)
                VALUES (?, 'MANAGEMENT_FEE', ?, ?, ?, ?, ?)
                ON CONFLICT (portfolio_id, accrual_date) WHERE type = 'MANAGEMENT_FEE' DO NOTHING
                """, portfolioId, description, amount, Date.valueOf(date), Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    public Map<String, Object> updateLiability(long id, String description, BigDecimal amount, LocalDate accrualDate,
                                               Instant now) {
        return jdbc.queryForMap("""
                UPDATE liability SET description = ?, amount = ?, accrual_date = ?, updated_at = ? WHERE id = ?
                RETURNING *
                """, description, amount, Date.valueOf(accrualDate), Timestamp.from(now), id);
    }

    public Map<String, Object> markPaid(long id, LocalDate paidDate, Instant now) {
        return jdbc.queryForMap("""
                UPDATE liability SET status = 'PAID', paid_date = ?, updated_at = ? WHERE id = ? RETURNING *
                """, Date.valueOf(paidDate), Timestamp.from(now), id);
    }

    public void deleteLiability(long id) {
        jdbc.update("DELETE FROM liability WHERE id = ?", id);
    }
}
