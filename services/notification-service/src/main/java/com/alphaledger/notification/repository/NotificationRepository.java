package com.alphaledger.notification.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {

    private static final String VISIBLE = "(:all OR n.audience_role IN (:roles))";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public record NewNotification(String type, String severity, String audienceRole, Long portfolioId,
                                  String reference, String title, String message) {
    }

    public long insert(NewNotification n, String emailStatus, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO notification (type, severity, audience_role, portfolio_id, reference, title, message,
                                          created_at, email_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, n.type(), n.severity(), n.audienceRole(), n.portfolioId(), n.reference(), n.title(),
                n.message(), Timestamp.from(now), emailStatus);
    }

    /** Notifications the caller can see (their roles, or all), newest first, with whether they have read each. */
    public List<Map<String, Object>> list(long userId, Collection<String> roles, boolean all, boolean unreadOnly,
                                          int page, int size) {
        return named.queryForList("""
                SELECT n.id, n.type, n.severity, n.audience_role, n.portfolio_id, n.reference, n.title, n.message,
                       n.created_at, n.email_status, r.read_at, (r.read_at IS NOT NULL) AS read
                FROM notification n
                LEFT JOIN notification_read r ON r.notification_id = n.id AND r.user_id = :userId
                WHERE %s AND (NOT :unreadOnly OR r.read_at IS NULL)
                ORDER BY n.created_at DESC, n.id DESC LIMIT :size OFFSET :offset
                """.formatted(VISIBLE), params(userId, roles, all).addValue("unreadOnly", unreadOnly)
                .addValue("size", size).addValue("offset", (long) page * size));
    }

    public long unreadCount(long userId, Collection<String> roles, boolean all) {
        return named.queryForObject("""
                SELECT count(*) FROM notification n
                WHERE %s AND NOT EXISTS (SELECT 1 FROM notification_read r
                                         WHERE r.notification_id = n.id AND r.user_id = :userId)
                """.formatted(VISIBLE), params(userId, roles, all), Long.class);
    }

    public boolean isVisible(long id, Collection<String> roles, boolean all) {
        return Boolean.TRUE.equals(named.queryForObject("SELECT EXISTS (SELECT 1 FROM notification n WHERE n.id = :id AND "
                + VISIBLE + ")", params(0, roles, all).addValue("id", id), Boolean.class));
    }

    /** Marks read; reading twice keeps the first time. */
    public void markRead(long id, long userId, Instant now) {
        jdbc.update("INSERT INTO notification_read (notification_id, user_id, read_at) VALUES (?, ?, ?) "
                + "ON CONFLICT DO NOTHING", id, userId, Timestamp.from(now));
    }

    public int markAllRead(long userId, Collection<String> roles, boolean all, Instant now) {
        return named.update("""
                INSERT INTO notification_read (notification_id, user_id, read_at)
                SELECT n.id, :userId, :now FROM notification n WHERE %s ON CONFLICT DO NOTHING
                """.formatted(VISIBLE), params(userId, roles, all).addValue("now", Timestamp.from(now)));
    }

    // Email outbox

    public record PendingEmail(long id, String audienceRole, String title, String message, int attempts) {
    }

    /** Claims pending emails; rows stay locked until the caller's transaction ends. */
    public List<PendingEmail> claimPendingEmails(int limit) {
        return jdbc.query("""
                SELECT id, audience_role, title, message, email_attempts FROM notification
                WHERE email_status = 'PENDING' ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, i) -> new PendingEmail(rs.getLong("id"), rs.getString("audience_role"), rs.getString("title"),
                rs.getString("message"), rs.getInt("email_attempts")), limit);
    }

    public void emailSent(long id, Instant now) {
        jdbc.update("UPDATE notification SET email_status = 'SENT', email_attempts = email_attempts + 1, "
                + "email_error = NULL, emailed_at = ? WHERE id = ?", Timestamp.from(now), id);
    }

    public void emailSkipped(long id) {
        jdbc.update("UPDATE notification SET email_status = 'SKIPPED' WHERE id = ?", id);
    }

    public void emailFailed(long id, String error, boolean giveUp) {
        jdbc.update("UPDATE notification SET email_attempts = email_attempts + 1, email_error = ?, email_status = ? "
                + "WHERE id = ?", error.substring(0, Math.min(error.length(), 500)), giveUp ? "FAILED" : "PENDING", id);
    }

    // Open breaks (digest)

    public void openBreak(long breakId, long runId, String type, String tradeRef, String isin, Long portfolioId,
                          Instant now) {
        jdbc.update("""
                INSERT INTO open_break (break_id, run_id, break_type, trade_ref, isin, portfolio_id, opened_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (break_id) DO NOTHING
                """, breakId, runId, type, tradeRef, isin, portfolioId, Timestamp.from(now));
    }

    public void closeBreak(long breakId) {
        jdbc.update("DELETE FROM open_break WHERE break_id = ?", breakId);
    }

    public List<Map<String, Object>> openBreaksByType() {
        return jdbc.queryForList("""
                SELECT break_type, count(*) AS count, min(opened_at) AS oldest
                FROM open_break GROUP BY break_type ORDER BY break_type
                """);
    }

    public Optional<Map<String, Object>> find(long id) {
        return jdbc.queryForList("SELECT * FROM notification WHERE id = ?", id).stream().findFirst();
    }

    private static MapSqlParameterSource params(long userId, Collection<String> roles, boolean all) {
        return new MapSqlParameterSource("userId", userId).addValue("all", all)
                .addValue("roles", roles.isEmpty() ? List.of("-") : roles);
    }
}
