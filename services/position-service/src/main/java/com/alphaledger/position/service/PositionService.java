package com.alphaledger.position.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alphaledger.common.event.EventEnvelope;
import com.alphaledger.common.event.EventTypes;
import com.alphaledger.common.event.PositionEvent;
import com.alphaledger.common.event.ProcessedEvents;
import com.alphaledger.common.event.Topics;
import com.alphaledger.common.event.TradeEvent;
import com.alphaledger.common.outbox.OutboxWriter;
import com.alphaledger.position.domain.PositionCalculator;
import com.alphaledger.position.domain.PositionCalculator.PositionState;
import com.alphaledger.position.repository.PositionRepository;
import com.alphaledger.position.repository.PositionRepository.PositionRow;

/**
 * Turns trade events into positions (FR-POS-1 to FR-POS-5). For each event, in one transaction: skip it if already
 * processed, record the trade (or its cancellation), rebuild the holding from all its trades, and publish
 * {@code position.updated} through the outbox.
 */
@Service
public class PositionService {

    private static final Logger log = LoggerFactory.getLogger(PositionService.class);

    private final PositionRepository repository;
    private final ProcessedEvents processedEvents;
    private final OutboxWriter outbox;
    private final Clock clock;

    public PositionService(PositionRepository repository, ProcessedEvents processedEvents, OutboxWriter outbox,
                           Clock clock) {
        this.repository = repository;
        this.processedEvents = processedEvents;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Applies {@code trade.created} ({@code cancelled = false}) or {@code trade.cancelled} ({@code true}). */
    @Transactional
    public boolean apply(EventEnvelope<TradeEvent> event, boolean cancelled) {
        if (!processedEvents.markProcessed(event.eventId())) {
            log.debug("Event {} already processed; skipped", event.eventId());
            return false;
        }
        TradeEvent trade = event.payload();
        Instant now = clock.instant();
        repository.upsertAppliedTrade(trade, cancelled, now);
        PositionState state = PositionCalculator.fold(repository.tradesFor(trade.portfolioId(), trade.instrumentId()));
        repository.upsertPosition(trade.portfolioId(), trade.instrumentId(), trade.isin(), state, now);
        if (!state.skipped().isEmpty()) {
            log.warn("Portfolio {} instrument {}: trades not applied: {}", trade.portfolioId(), trade.instrumentId(),
                    state.skipped());
            repository.recordSkips(trade.portfolioId(), trade.instrumentId(), state.skipped(), now);
        }
        outbox.append("Position", trade.portfolioId() + ":" + trade.instrumentId(), EventTypes.POSITION_UPDATED,
                Topics.POSITION_UPDATED, String.valueOf(trade.portfolioId()),
                new PositionEvent(trade.portfolioId(), trade.instrumentId(), trade.isin(), state.quantity(),
                        state.avgCost(), state.realizedPnl(), state.lastTradeId(), now));
        return true;
    }

    @Transactional(readOnly = true)
    public List<PositionRow> positions(long portfolioId, boolean includeClosed) {
        return repository.positions(portfolioId, includeClosed);
    }

    @Transactional(readOnly = true)
    public List<PositionRow> snapshot(long portfolioId, LocalDate date) {
        return repository.snapshots(portfolioId, date);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> exceptions(long portfolioId) {
        return repository.exceptions(portfolioId);
    }
}
