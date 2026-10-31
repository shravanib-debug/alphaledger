package com.alphaledger.reconciliation.run;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.alphaledger.common.event.EventTypes;
import com.alphaledger.common.event.ReconBreakEvent;
import com.alphaledger.common.event.Topics;
import com.alphaledger.common.outbox.OutboxWriter;
import com.alphaledger.reconciliation.matching.MatchingEngine;
import com.alphaledger.reconciliation.matching.MatchingEngine.ExternalRecord;
import com.alphaledger.reconciliation.matching.MatchingEngine.Finding;
import com.alphaledger.reconciliation.matching.MatchingEngine.InternalTrade;
import com.alphaledger.reconciliation.matching.MatchingEngine.Result;
import com.alphaledger.reconciliation.repository.ReconRepository;

/**
 * Match step of a run (architecture.md §5.5, steps 5 to 9): compare the staged custodian records with the trades
 * settled on the run date, store one OPEN break per finding (with a CREATED audit entry), publish
 * {@code recon.break.created} through the outbox and write the run summary. All in one transaction.
 */
@Service
public class ReconMatchService {

    private static final Logger log = LoggerFactory.getLogger(ReconMatchService.class);

    private final ReconRepository repository;
    private final MatchingEngine engine;
    private final OutboxWriter outbox;
    private final JsonMapper jsonMapper;
    private final java.time.Clock clock;

    public ReconMatchService(ReconRepository repository, MatchingEngine engine, OutboxWriter outbox,
                             JsonMapper jsonMapper, java.time.Clock clock) {
        this.repository = repository;
        this.engine = engine;
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional
    public Result matchAndRaiseBreaks(long runId, LocalDate runDate) {
        List<ExternalRecord> external = repository.externalRecords(runId);
        List<InternalTrade> internal = repository.settledOn(runDate);
        Result result = engine.match(external, internal);
        Instant now = clock.instant();
        for (Finding f : result.findings()) {
            long breakId = repository.insertBreak(runId, f, now);
            Map<String, Object> state = repository.findBreak(breakId).orElseThrow();
            repository.insertAudit(breakId, "CREATED", null, "Raised by reconciliation run " + runId, null,
                    jsonMapper.writeValueAsString(state), now);
            outbox.append("ReconBreak", String.valueOf(breakId), EventTypes.RECON_BREAK_CREATED,
                    Topics.RECON_BREAK_CREATED, String.valueOf(runId), BreakEvents.of(state));
        }
        Map<String, Long> byType = result.findings().stream()
                .collect(Collectors.groupingBy(f -> f.type().name(), Collectors.counting()));
        int errorRows = repository.errorCount(runId);
        repository.completeRun(runId, external.size() + errorRows, errorRows, internal.size(), result.matched(),
                byType, clock.instant());
        repository.deleteRawRows(runId);
        log.info("Reconciliation run {} ({}): {} matched, breaks {}", runId, runDate, result.matched(), byType);
        return result;
    }

    /** Builds event payloads from a break row. */
    public static final class BreakEvents {

        private BreakEvents() {
        }

        public static ReconBreakEvent of(Map<String, Object> b) {
            Function<String, Long> asLong = k -> b.get(k) == null ? null : ((Number) b.get(k)).longValue();
            return new ReconBreakEvent(((Number) b.get("id")).longValue(), ((Number) b.get("run_id")).longValue(),
                    (String) b.get("break_type"), (String) b.get("trade_ref"), (String) b.get("isin"),
                    ((java.sql.Date) b.get("trade_date")).toLocalDate(), asLong.apply("portfolio_id"),
                    (java.math.BigDecimal) b.get("internal_qty"), (java.math.BigDecimal) b.get("external_qty"),
                    (java.math.BigDecimal) b.get("internal_price"), (java.math.BigDecimal) b.get("external_price"),
                    (String) b.get("status"), (String) b.get("resolution_type"), asLong.apply("assigned_to"));
        }
    }
}
