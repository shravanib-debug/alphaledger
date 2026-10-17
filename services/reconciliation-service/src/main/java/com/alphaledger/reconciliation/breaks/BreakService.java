package com.alphaledger.reconciliation.breaks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.alphaledger.common.error.NotFoundException;
import com.alphaledger.common.event.EventTypes;
import com.alphaledger.common.event.Topics;
import com.alphaledger.common.outbox.OutboxWriter;
import com.alphaledger.common.security.AuthenticatedUser;
import com.alphaledger.reconciliation.breaks.BreakWorkflow.Action;
import com.alphaledger.reconciliation.breaks.BreakWorkflow.BreakStatus;
import com.alphaledger.reconciliation.repository.ReconRepository;
import com.alphaledger.reconciliation.run.ReconMatchService.BreakEvents;

/**
 * Break workflow (FR-REC-7 to FR-REC-10, FR-REC-12). Each action locks the break row, applies {@link BreakWorkflow},
 * writes an immutable audit entry with the state before and after, and publishes {@code recon.break.resolved} when a
 * break is resolved. Ageing: days since the break was raised (until resolved); past the SLA it is flagged overdue.
 */
@Service
public class BreakService {

    private final ReconRepository repository;
    private final OutboxWriter outbox;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final int slaDays;

    public BreakService(ReconRepository repository, OutboxWriter outbox, JsonMapper jsonMapper, Clock clock,
                        @Value("${alphaledger.recon.sla-days:3}") int slaDays) {
        this.repository = repository;
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.slaDays = slaDays;
    }

    @Transactional
    public Map<String, Object> act(long breakId, Action action, Long assignee, String comment, AuthenticatedUser user) {
        Map<String, Object> before = repository.breakForUpdate(breakId)
                .orElseThrow(() -> new NotFoundException("Break " + breakId + " not found"));
        BreakStatus next = BreakWorkflow.next(BreakStatus.valueOf((String) before.get("status")), action, comment);
        Instant now = clock.instant();
        Long assignedTo = action == Action.ASSIGN ? (assignee != null ? assignee : user.id())
                : before.get("assigned_to") == null ? user.id() : ((Number) before.get("assigned_to")).longValue();
        repository.updateBreak(breakId, next.name(), assignedTo, action.isResolution() ? action.name() : null,
                action.isResolution() ? comment.trim() : null, next == BreakStatus.RESOLVED ? now : null, now);
        Map<String, Object> after = repository.findBreak(breakId).orElseThrow();
        repository.insertAudit(breakId, action.name(), user.id(), comment.trim(), jsonMapper.writeValueAsString(before),
                jsonMapper.writeValueAsString(after), now);
        if (next == BreakStatus.RESOLVED) {
            outbox.append("ReconBreak", String.valueOf(breakId), EventTypes.RECON_BREAK_RESOLVED,
                    Topics.RECON_BREAK_RESOLVED, String.valueOf(breakId), BreakEvents.of(after));
        }
        return withAgeing(after);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(long breakId) {
        return withAgeing(repository.findBreak(breakId)
                .orElseThrow(() -> new NotFoundException("Break " + breakId + " not found")));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, String type, Long runId, Long assignedTo, Integer minAgeDays,
                                          int page, int size) {
        return repository.breaks(status, type, runId, assignedTo, size, page * size).stream()
                .map(this::withAgeing)
                .filter(b -> minAgeDays == null || (int) b.get("age_days") >= minAgeDays)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> audit(long breakId) {
        get(breakId);
        return repository.audit(breakId);
    }

    /** Adds {@code age_days} and {@code overdue} (open longer than the SLA). */
    Map<String, Object> withAgeing(Map<String, Object> row) {
        Map<String, Object> b = new LinkedHashMap<>(row);
        Instant created = ((java.sql.Timestamp) row.get("created_at")).toInstant();
        Instant end = row.get("resolved_at") == null ? clock.instant()
                : ((java.sql.Timestamp) row.get("resolved_at")).toInstant();
        long age = Duration.between(created, end).toDays();
        String status = (String) row.get("status");
        b.put("age_days", (int) age);
        b.put("overdue", !"RESOLVED".equals(status) && age > slaDays);
        return b;
    }
}
