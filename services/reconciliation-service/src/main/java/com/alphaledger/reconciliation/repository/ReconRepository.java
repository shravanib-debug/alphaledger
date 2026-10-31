package com.alphaledger.reconciliation.repository;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.alphaledger.common.event.TradeEvent;
import com.alphaledger.reconciliation.matching.MatchingEngine.ExternalRecord;
import com.alphaledger.reconciliation.matching.MatchingEngine.Finding;
import com.alphaledger.reconciliation.matching.MatchingEngine.InternalTrade;
import com.alphaledger.reconciliation.run.StagedRow;

/** SQL for settled trades, runs, staged custodian rows, breaks and their audit trail. */
@Repository
public class ReconRepository {

    private final JdbcTemplate jdbc;

    public ReconRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- internal side ----------

    public void upsertSettledTrade(TradeEvent t, Instant now) {
        jdbc.update("""
                INSERT INTO settled_trade_view (trade_id, trade_ref, portfolio_id, instrument_id, isin, side, quantity,
                                                price, trade_date, settle_date, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (trade_id) DO NOTHING
                """, t.tradeId(), t.tradeRef(), t.portfolioId(), t.instrumentId(), t.isin(), t.side(), t.quantity(),
                t.price(), Date.valueOf(t.tradeDate()), Date.valueOf(t.settleDate()), Timestamp.from(now));
    }

    /** Internal trades in scope for a run: everything settled on the run date. */
    public List<InternalTrade> settledOn(LocalDate settleDate) {
        return jdbc.query("""
                SELECT trade_id, trade_ref, portfolio_id, isin, trade_date, side, quantity, price
                FROM settled_trade_view WHERE settle_date = ? ORDER BY trade_id
                """, (rs, i) -> new InternalTrade(rs.getLong("trade_id"), rs.getString("trade_ref"),
                rs.getLong("portfolio_id"), rs.getString("isin"), rs.getObject("trade_date", LocalDate.class),
                rs.getString("side"), rs.getBigDecimal("quantity"), rs.getBigDecimal("price")),
                Date.valueOf(settleDate));
    }

    // ---------- runs ----------

    public Optional<Long> findRun(LocalDate runDate, String source, String checksum) {
        return jdbc.queryForList("SELECT id FROM recon_run WHERE run_date = ? AND source = ? AND file_checksum = ?",
                Long.class, Date.valueOf(runDate), source, checksum).stream().findFirst();
    }

    public long insertRun(LocalDate runDate, String source, String fileName, String checksum, Long createdBy,
                          Instant now) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO recon_run (run_date, source, file_name, file_checksum, status, created_by, started_at)
                    VALUES (?, ?, ?, ?, 'RUNNING', ?, ?)
                    """, new String[] {"id"});
            ps.setDate(1, Date.valueOf(runDate));
            ps.setString(2, source);
            ps.setString(3, fileName);
            ps.setString(4, checksum);
            ps.setObject(5, createdBy);
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public void insertFile(long runId, InputStream content, long length) {
        jdbc.update("INSERT INTO custodian_file (run_id, content) VALUES (?, ?)", ps -> {
            ps.setLong(1, runId);
            ps.setBinaryStream(2, content, length);
        });
    }

    /** Up to {@code length} bytes of the stored file from a zero-based offset; empty past the end. */
    public byte[] fileSlice(long runId, long offset, int length) {
        return jdbc.queryForObject("SELECT substring(content FROM ?::int FOR ?::int) FROM custodian_file WHERE run_id = ?",
                byte[].class, Math.toIntExact(offset + 1), length, runId);
    }

    /** Puts a failed run back to RUNNING. False when it is not FAILED, so only one caller can restart it. */
    public boolean reopenRun(long runId, Instant now) {
        return jdbc.update("UPDATE recon_run SET status = 'RUNNING', error_message = NULL, completed_at = NULL, "
                + "started_at = ? WHERE id = ? AND status = 'FAILED'", Timestamp.from(now), runId) == 1;
    }

    public List<Long> runningStartedBefore(Instant cutoff) {
        return jdbc.queryForList("SELECT id FROM recon_run WHERE status = 'RUNNING' AND started_at < ?", Long.class,
                Timestamp.from(cutoff));
    }

    public void completeRun(long runId, int totalRecords, int errorRows, int internalTrades, int matched,
                            Map<String, Long> breaksByType, Instant now) {
        int breaks = breaksByType.values().stream().mapToInt(Long::intValue).sum();
        jdbc.update("""
                UPDATE recon_run SET status = 'COMPLETED', total_records = ?, error_rows = ?, internal_trades = ?,
                    matched_count = ?, break_count = ?, missing_internal = ?, missing_external = ?, qty_mismatch = ?,
                    price_mismatch = ?, completed_at = ? WHERE id = ?
                """, totalRecords, errorRows, internalTrades, matched, breaks,
                breaksByType.getOrDefault("MISSING_INTERNAL", 0L), breaksByType.getOrDefault("MISSING_EXTERNAL", 0L),
                breaksByType.getOrDefault("QTY_MISMATCH", 0L), breaksByType.getOrDefault("PRICE_MISMATCH", 0L),
                Timestamp.from(now), runId);
    }

    public void failRun(long runId, String message, Instant now) {
        jdbc.update("UPDATE recon_run SET status = 'FAILED', error_message = ?, completed_at = ? WHERE id = ?",
                message == null ? null : message.substring(0, Math.min(500, message.length())), Timestamp.from(now),
                runId);
    }

    public Optional<Map<String, Object>> run(long runId) {
        return jdbc.queryForList("SELECT * FROM recon_run WHERE id = ?", runId).stream().findFirst();
    }

    public List<Map<String, Object>> runs(int limit) {
        return jdbc.queryForList("SELECT * FROM recon_run ORDER BY id DESC LIMIT ?", limit);
    }

    // ---------- staging ----------

    public void insertRawRows(long runId, List<? extends StagedRow.RawRow> rows) {
        jdbc.batchUpdate("INSERT INTO custodian_raw_row (run_id, row_number, line) VALUES (?, ?, ?) "
                + "ON CONFLICT (run_id, row_number) DO NOTHING",
                rows.stream().map(r -> new Object[] {runId, r.rowNumber(), r.line()}).toList());
    }

    /** Lowest and highest stored row number; {1, 0} (an empty range) when the file has no rows. */
    public int[] rawRowBounds(long runId) {
        return jdbc.queryForObject("SELECT COALESCE(min(row_number), 1), COALESCE(max(row_number), 0) "
                + "FROM custodian_raw_row WHERE run_id = ?", (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)}, runId);
    }

    public void deleteRawRows(long runId) {
        jdbc.update("DELETE FROM custodian_raw_row WHERE run_id = ?", runId);
    }

    /** Moves every repeat of a match key to the error table; the earliest file row stays. */
    public void moveDuplicatesToErrors(long runId) {
        jdbc.update("""
                WITH dup AS (
                    SELECT id, row_number, raw_row FROM (
                        SELECT id, row_number, raw_row, ROW_NUMBER() OVER (
                            PARTITION BY trade_ref, isin, trade_date ORDER BY row_number) AS occurrence
                        FROM custodian_record WHERE run_id = ?) ranked
                    WHERE occurrence > 1
                ), moved AS (
                    INSERT INTO custodian_record_error (run_id, row_number, error_message, raw_row)
                    SELECT ?, row_number, 'duplicate trade_ref + isin + trade_date in the file', raw_row FROM dup
                    ON CONFLICT (run_id, row_number) DO NOTHING
                )
                DELETE FROM custodian_record WHERE id IN (SELECT id FROM dup)
                """, runId, runId);
    }

    public void insertStaged(List<? extends StagedRow> rows) {
        List<Object[]> records = new ArrayList<>();
        List<Object[]> errors = new ArrayList<>();
        for (StagedRow r : rows) {
            if (r.error() != null) {
                String error = r.error().length() > 500 ? r.error().substring(0, 500) : r.error();
                errors.add(new Object[] {r.runId(), r.rowNumber(), error, r.rawRow()});
            } else {
                ExternalRecord e = r.record();
                records.add(new Object[] {r.runId(), r.rowNumber(), e.tradeRef(), e.isin(), Date.valueOf(e.tradeDate()),
                        Date.valueOf(r.settleDate()), e.side(), e.quantity(), e.price(), r.rawRow()});
            }
        }
        if (!records.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO custodian_record (run_id, row_number, trade_ref, isin, trade_date, settle_date, side,
                                                  quantity, price, raw_row)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (run_id, row_number) DO NOTHING
                    """, records);
        }
        if (!errors.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO custodian_record_error (run_id, row_number, error_message, raw_row) "
                    + "VALUES (?, ?, ?, ?) ON CONFLICT (run_id, row_number) DO NOTHING", errors);
        }
    }

    public List<ExternalRecord> externalRecords(long runId) {
        return jdbc.query("""
                SELECT row_number, trade_ref, isin, trade_date, side, quantity, price FROM custodian_record
                WHERE run_id = ? ORDER BY row_number
                """, (rs, i) -> new ExternalRecord(rs.getInt("row_number"), rs.getString("trade_ref"),
                rs.getString("isin"), rs.getObject("trade_date", LocalDate.class), rs.getString("side"),
                rs.getBigDecimal("quantity"), rs.getBigDecimal("price")), runId);
    }

    public int errorCount(long runId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM custodian_record_error WHERE run_id = ?",
                Integer.class, runId);
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> runErrors(long runId) {
        return jdbc.queryForList("SELECT row_number, error_message, raw_row FROM custodian_record_error "
                + "WHERE run_id = ? ORDER BY row_number", runId);
    }

    // ---------- breaks ----------

    public long insertBreak(long runId, Finding f, Instant now) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO recon_break (run_id, break_type, trade_ref, isin, trade_date, portfolio_id,
                        internal_trade_id, internal_qty, external_qty, internal_price, external_price, status,
                        created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, runId);
            ps.setString(2, f.type().name());
            ps.setString(3, f.tradeRef());
            ps.setString(4, f.isin());
            ps.setDate(5, Date.valueOf(f.tradeDate()));
            ps.setObject(6, f.internal() == null ? null : f.internal().portfolioId());
            ps.setObject(7, f.internal() == null ? null : f.internal().tradeId());
            ps.setBigDecimal(8, f.internal() == null ? null : signed(f.internal().side(), f.internal().quantity()));
            ps.setBigDecimal(9, f.external() == null ? null : signed(f.external().side(), f.external().quantity()));
            ps.setBigDecimal(10, f.internal() == null ? null : f.internal().price());
            ps.setBigDecimal(11, f.external() == null ? null : f.external().price());
            ps.setTimestamp(12, Timestamp.from(now));
            ps.setTimestamp(13, Timestamp.from(now));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public Optional<Map<String, Object>> breakForUpdate(long breakId) {
        return jdbc.queryForList("SELECT * FROM recon_break WHERE id = ? FOR UPDATE", breakId).stream().findFirst();
    }

    public Optional<Map<String, Object>> findBreak(long breakId) {
        return jdbc.queryForList("SELECT * FROM recon_break WHERE id = ?", breakId).stream().findFirst();
    }

    public void updateBreak(long breakId, String status, Long assignedTo, String resolutionType, String comment,
                            Instant resolvedAt, Instant now) {
        jdbc.update("""
                UPDATE recon_break SET status = ?, assigned_to = ?, resolution_type = COALESCE(?, resolution_type),
                    resolution_comment = COALESCE(?, resolution_comment), resolved_at = ?, updated_at = ?,
                    version = version + 1
                WHERE id = ?
                """, status, assignedTo, resolutionType, comment, resolvedAt == null ? null : Timestamp.from(resolvedAt),
                Timestamp.from(now), breakId);
    }

    public void insertAudit(long breakId, String action, Long performedBy, String comment, String beforeJson,
                            String afterJson, Instant now) {
        jdbc.update("""
                INSERT INTO break_action_audit (break_id, action, performed_by, comment, before_state, after_state,
                                                performed_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                """, breakId, action, performedBy, comment, beforeJson, afterJson, Timestamp.from(now));
    }

    public List<Map<String, Object>> audit(long breakId) {
        return jdbc.queryForList("SELECT id, action, performed_by, comment, before_state::text AS before_state, "
                + "after_state::text AS after_state, performed_at FROM break_action_audit WHERE break_id = ? ORDER BY id",
                breakId);
    }

    /** Break list with optional filters (FR-UI-5); null arguments are ignored. */
    public List<Map<String, Object>> breaks(String status, String type, Long runId, Long assignedTo, int limit,
                                            int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM recon_break WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (type != null) {
            sql.append(" AND break_type = ?");
            args.add(type);
        }
        if (runId != null) {
            sql.append(" AND run_id = ?");
            args.add(runId);
        }
        if (assignedTo != null) {
            sql.append(" AND assigned_to = ?");
            args.add(assignedTo);
        }
        sql.append(" ORDER BY created_at, id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private static BigDecimal signed(String side, BigDecimal quantity) {
        return "SELL".equals(side) ? quantity.negate() : quantity;
    }
}
