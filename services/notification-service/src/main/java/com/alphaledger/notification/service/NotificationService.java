package com.alphaledger.notification.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alphaledger.common.error.NotFoundException;
import com.alphaledger.common.event.EventEnvelope;
import com.alphaledger.common.event.InvalidEventException;
import com.alphaledger.common.event.LimitBreachEvent;
import com.alphaledger.common.event.ProcessedEvents;
import com.alphaledger.common.event.ReconBreakEvent;
import com.alphaledger.common.security.AuthenticatedUser;
import com.alphaledger.common.security.Roles;
import com.alphaledger.notification.config.NotificationProperties;
import com.alphaledger.notification.repository.NotificationRepository;
import com.alphaledger.notification.repository.NotificationRepository.NewNotification;

/**
 * Turns events into notifications for a role (FR-NOT-1, FR-NOT-2): reconciliation breaks go to Ops, limit breaches to
 * PMs. Admin and Auditor see every notification. Each event is handled once ({@code processed_event}); the email is
 * queued in the same transaction and sent later by {@link EmailDispatcher}.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Set<String> SEE_ALL = Set.of(Roles.ADMIN, Roles.AUDITOR);

    private final NotificationRepository repository;
    private final ProcessedEvents processedEvents;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, ProcessedEvents processedEvents,
                               NotificationProperties properties, Clock clock) {
        this.repository = repository;
        this.processedEvents = processedEvents;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void onBreakCreated(EventEnvelope<ReconBreakEvent> event) {
        if (!processedEvents.markProcessed(event.eventId())) {
            return;
        }
        ReconBreakEvent b = event.payload();
        if (b.breakType() == null) {
            throw new InvalidEventException("recon.break.created without breakType");
        }
        repository.openBreak(b.breakId(), b.runId(), b.breakType(), b.tradeRef(), b.isin(), b.portfolioId(),
                clock.instant());
        String subject = b.tradeRef() == null ? b.isin() : b.tradeRef();
        create(new NewNotification("RECON_BREAK", "WARNING", Roles.OPS_ANALYST, b.portfolioId(),
                String.valueOf(b.breakId()),
                "Reconciliation break: " + label(b.breakType()) + " on " + subject,
                "Run " + b.runId() + " found a " + label(b.breakType()).toLowerCase() + " for trade "
                        + (b.tradeRef() == null ? "(none)" : b.tradeRef()) + ", ISIN " + b.isin() + ", trade date "
                        + b.tradeDate() + ". Internal: " + qtyPrice(b.internalQuantity(), b.internalPrice())
                        + "; custodian: " + qtyPrice(b.externalQuantity(), b.externalPrice()) + ". Break "
                        + b.breakId() + " is open."));
    }

    @Transactional
    public void onBreakResolved(EventEnvelope<ReconBreakEvent> event) {
        if (processedEvents.markProcessed(event.eventId())) {
            repository.closeBreak(event.payload().breakId());
        }
    }

    @Transactional
    public void onLimitBreached(EventEnvelope<LimitBreachEvent> event) {
        if (!processedEvents.markProcessed(event.eventId())) {
            return;
        }
        LimitBreachEvent l = event.payload();
        if (l.assetClass() == null || l.exposurePct() == null || l.maxPct() == null) {
            throw new InvalidEventException("limit.breached without assetClass, exposurePct or maxPct");
        }
        create(new NewNotification("LIMIT_BREACH", "CRITICAL", Roles.PM, l.portfolioId(),
                l.portfolioId() + ":" + l.assetClass() + ":" + l.asOfDate(),
                "Limit breached: " + l.assetClass() + " at " + pct(l.exposurePct()) + "% in portfolio "
                        + l.portfolioId(),
                "On " + l.asOfDate() + ", " + l.assetClass() + " was " + pct(l.exposurePct())
                        + "% of portfolio " + l.portfolioId() + "'s market value (" + l.marketValue()
                        + " INR), above its limit of " + pct(l.maxPct()) + "%."));
    }

    /** Daily digest of open breaks for Ops; returns false when nothing is open. */
    @Transactional
    public boolean openBreaksDigest() {
        List<Map<String, Object>> open = repository.openBreaksByType();
        if (open.isEmpty()) {
            return false;
        }
        long total = 0;
        StringBuilder lines = new StringBuilder();
        Instant now = clock.instant();
        for (Map<String, Object> row : open) {
            long count = ((Number) row.get("count")).longValue();
            total += count;
            Instant oldest = ((java.sql.Timestamp) row.get("oldest")).toInstant();
            lines.append("- ").append(label((String) row.get("break_type"))).append(": ").append(count)
                    .append(" (oldest ").append(Duration.between(oldest, now).toDays()).append(" days)\n");
        }
        create(new NewNotification("OPEN_BREAKS_DIGEST", "INFO", Roles.OPS_ANALYST, null, null,
                total + " reconciliation breaks still open",
                "Open reconciliation breaks by type:\n" + lines));
        return true;
    }

    private void create(NewNotification n) {
        boolean email = properties.email().enabled() && !properties.recipientsFor(n.audienceRole()).isEmpty();
        long id = repository.insert(n, email ? "PENDING" : "SKIPPED", clock.instant());
        log.info("Notification {} for {}: {}", id, n.audienceRole(), n.title());
    }

    // Reading

    public List<Map<String, Object>> list(AuthenticatedUser user, boolean unreadOnly, int page, int size) {
        return repository.list(user.id(), user.roles(), seesAll(user), unreadOnly, page, size);
    }

    public long unreadCount(AuthenticatedUser user) {
        return repository.unreadCount(user.id(), user.roles(), seesAll(user));
    }

    @Transactional
    public void markRead(AuthenticatedUser user, long id) {
        if (!repository.isVisible(id, user.roles(), seesAll(user))) {
            throw new NotFoundException("Notification " + id + " not found");
        }
        repository.markRead(id, user.id(), clock.instant());
    }

    @Transactional
    public int markAllRead(AuthenticatedUser user) {
        return repository.markAllRead(user.id(), user.roles(), seesAll(user), clock.instant());
    }

    private static boolean seesAll(AuthenticatedUser user) {
        return user.roles().stream().anyMatch(SEE_ALL::contains);
    }

    private static String label(String breakType) {
        return breakType.charAt(0) + breakType.substring(1).toLowerCase().replace('_', ' ');
    }

    private static String qtyPrice(BigDecimal qty, BigDecimal price) {
        return qty == null ? "none" : qty.stripTrailingZeros().toPlainString() + " @ "
                + (price == null ? "-" : price.stripTrailingZeros().toPlainString());
    }

    private static String pct(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
