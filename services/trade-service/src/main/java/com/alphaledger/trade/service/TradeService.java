package com.alphaledger.trade.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alphaledger.common.error.BusinessRuleException;
import com.alphaledger.common.error.ConflictException;
import com.alphaledger.common.error.NotFoundException;
import com.alphaledger.common.error.ValidationException;
import com.alphaledger.common.event.EventTypes;
import com.alphaledger.common.event.TradeEvent;
import com.alphaledger.common.event.Topics;
import com.alphaledger.common.instrument.Isin;
import com.alphaledger.common.security.AuthenticatedUser;
import com.alphaledger.common.web.PageResponse;
import com.alphaledger.common.calendar.BusinessDayCalendar;
import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.trade.client.PortfolioClient;
import com.alphaledger.trade.client.PortfolioClient.InstrumentInfo;
import com.alphaledger.trade.client.PortfolioClient.PortfolioInfo;
import com.alphaledger.trade.config.TradeProperties;
import com.alphaledger.trade.domain.Trade;
import com.alphaledger.trade.domain.TradeAudit;
import com.alphaledger.trade.domain.TradeSide;
import com.alphaledger.trade.domain.TradeStatus;
import com.alphaledger.trade.dto.CreateTradeRequest;
import com.alphaledger.trade.dto.TradeAuditResponse;
import com.alphaledger.trade.dto.TradeResponse;
import com.alphaledger.trade.outbox.OutboxWriter;
import com.alphaledger.trade.repository.TradeAuditRepository;
import com.alphaledger.trade.repository.TradeRepository;

/**
 * Trade capture and lifecycle (FR-TR-1 to FR-TR-9).
 * <ul>
 * <li>Malformed requests (bad dates, non-positive amounts) are refused with 400 and not stored.</li>
 * <li>Well-formed trades that break a business rule (unknown or archived portfolio, unknown or inactive
 * instrument, SELL above the holding) are stored as REJECTED with a reason, so the blotter shows them.</li>
 * <li>Every status change writes a {@code trade_audit} row; VALIDATED, SETTLED and CANCELLED also write an outbox
 * event in the same transaction.</li>
 * </ul>
 */
@Service
public class TradeService {

    private final TradeRepository trades;
    private final TradeAuditRepository audits;
    private final PortfolioClient portfolioClient;
    private final BusinessDayCalendar calendar;
    private final MarketClock clock;
    private final HoldingGuard holdings;
    private final OutboxWriter outbox;
    private final TradeRefGenerator refs;
    private final int settlementDays;

    public TradeService(TradeRepository trades, TradeAuditRepository audits, PortfolioClient portfolioClient,
                        BusinessDayCalendar calendar, MarketClock clock, HoldingGuard holdings, OutboxWriter outbox,
                        TradeRefGenerator refs, TradeProperties properties) {
        this.trades = trades;
        this.audits = audits;
        this.portfolioClient = portfolioClient;
        this.calendar = calendar;
        this.clock = clock;
        this.holdings = holdings;
        this.outbox = outbox;
        this.refs = refs;
        this.settlementDays = properties.settlementDays();
    }

    /** Result of a create: the trade, and whether it already existed (idempotent replay of the same tradeRef). */
    public record CreateResult(TradeResponse trade, boolean replayed) {
    }

    @Transactional
    public CreateResult create(CreateTradeRequest request, AuthenticatedUser user) {
        String isin = Isin.normalize(request.isin());
        LocalDate tradeDate = request.tradeDate() != null ? request.tradeDate() : clock.today();
        LocalDate settleDate = resolveSettleDate(tradeDate, request.settleDate());

        if (request.tradeRef() != null) {
            Trade existing = trades.findByTradeRef(request.tradeRef().trim()).orElse(null);
            if (existing != null) {
                if (!sameTrade(existing, request, isin, tradeDate, settleDate)) {
                    throw new ConflictException("Trade reference " + existing.getTradeRef()
                            + " already exists with different details");
                }
                return new CreateResult(TradeResponse.from(existing), true);
            }
        }

        PortfolioInfo portfolio = portfolioClient.findPortfolio(request.portfolioId()).orElse(null);
        if (portfolio != null && !user.isAdmin() && portfolio.managerId() != user.id()) {
            throw new AccessDeniedException("Portfolio " + portfolio.id() + " is managed by another user");
        }
        InstrumentInfo instrument = portfolioClient.findInstrumentByIsin(isin).orElse(null);

        Instant now = clock.now();
        String tradeRef = request.tradeRef() != null ? request.tradeRef().trim() : refs.next(tradeDate);
        Trade trade = trades.save(Trade.capture(tradeRef, request.portfolioId(),
                instrument == null ? null : instrument.id(), isin, request.side(), request.quantity(),
                request.price(), tradeDate, settleDate, user.id(), now));
        audit(trade, null, user.id(), "Captured");

        String reason = rejectionReason(request.portfolioId(), portfolio, isin, instrument);
        if (reason == null && request.side() == TradeSide.SELL) {
            BigDecimal held = holdings.lockAndGet(trade.getPortfolioId(), trade.getInstrumentId(), trade.getId());
            if (held.compareTo(trade.getQuantity()) < 0) {
                reason = "SELL of " + trade.getQuantity().stripTrailingZeros().toPlainString()
                        + " exceeds the current holding of " + held.stripTrailingZeros().toPlainString();
            }
        }
        if (reason != null) {
            trade.reject(reason, now);
            audit(trade, TradeStatus.NEW, user.id(), reason);
            return new CreateResult(TradeResponse.from(trade), false);
        }

        trade.validate(now);
        audit(trade, TradeStatus.NEW, user.id(), "Validated");
        publish(trade, EventTypes.TRADE_CREATED, Topics.TRADE_CREATED);
        return new CreateResult(TradeResponse.from(trade), false);
    }

    /** Cancel before settlement (FR-TR-6): Admin, or the PM who manages the portfolio. */
    @Transactional
    public TradeResponse cancel(long id, String comment, AuthenticatedUser user) {
        Trade trade = find(id);
        if (!user.isAdmin()) {
            PortfolioInfo portfolio = portfolioClient.findPortfolio(trade.getPortfolioId())
                    .orElseThrow(() -> new NotFoundException("Portfolio " + trade.getPortfolioId() + " not found"));
            if (portfolio.managerId() != user.id()) {
                throw new AccessDeniedException("Portfolio " + portfolio.id() + " is managed by another user");
            }
        }
        if (trade.getStatus() == TradeStatus.VALIDATED && trade.getSide() == TradeSide.BUY) {
            BigDecimal heldWithout = holdings.lockAndGet(trade.getPortfolioId(), trade.getInstrumentId(), trade.getId());
            if (heldWithout.signum() < 0) {
                throw new BusinessRuleException("Cancelling " + trade.getTradeRef()
                        + " would leave a negative holding; cancel the later SELL first");
            }
        }
        TradeStatus from = trade.getStatus();
        trade.cancel(clock.now());
        audit(trade, from, user.id(), comment == null || comment.isBlank() ? "Cancelled" : comment.trim());
        publish(trade, EventTypes.TRADE_CANCELLED, Topics.TRADE_CANCELLED);
        return TradeResponse.from(trade);
    }

    /** Manual settlement by an Admin (Flow B); normally the auto-settlement job does this. */
    @Transactional
    public TradeResponse settle(long id, String comment, AuthenticatedUser user) {
        Trade trade = find(id);
        if (clock.today().isBefore(trade.getTradeDate())) {
            throw new BusinessRuleException("Trade " + trade.getTradeRef() + " cannot settle before its trade date");
        }
        TradeStatus from = trade.getStatus();
        trade.settle(clock.now());
        audit(trade, from, user.id(), comment == null || comment.isBlank() ? "Settled manually" : comment.trim());
        publish(trade, EventTypes.TRADE_SETTLED, Topics.TRADE_SETTLED);
        return TradeResponse.from(trade);
    }

    /**
     * Settles one batch of VALIDATED trades whose settle date has arrived (FR-TR-8). Called repeatedly by
     * {@link AutoSettlementJob}; returns the number settled. Only runs on business days.
     */
    @Transactional
    public int settleDueBatch(int batchSize) {
        LocalDate today = clock.today();
        if (!calendar.isBusinessDay(today)) {
            return 0;
        }
        List<Long> due = trades.lockDueForSettlement(today, batchSize);
        Instant now = clock.now();
        for (Trade trade : trades.findAllById(due)) {
            trade.settle(now);
            audit(trade, TradeStatus.VALIDATED, null, "Auto-settled on settle date " + trade.getSettleDate());
            publish(trade, EventTypes.TRADE_SETTLED, Topics.TRADE_SETTLED);
        }
        return due.size();
    }

    @Transactional(readOnly = true)
    public PageResponse<TradeResponse> blotter(Long portfolioId, TradeStatus status, String isin, TradeSide side,
                                               LocalDate from, LocalDate to, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ValidationException("from: must be on or before 'to'");
        }
        Page<Trade> result = trades.findAll(TradeRepository.blotter(portfolioId, status, isin, side, from, to),
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("tradeDate"), Sort.Order.desc("id"))));
        return PageResponse.of(result.map(TradeResponse::from).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public TradeResponse get(long id) {
        return TradeResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public List<TradeAuditResponse> auditTrail(long id) {
        find(id);
        return audits.findByTradeIdOrderByIdAsc(id).stream().map(TradeAuditResponse::from).toList();
    }

    LocalDate resolveSettleDate(LocalDate tradeDate, LocalDate requestedSettleDate) {
        if (tradeDate.isAfter(clock.today())) {
            throw new ValidationException("tradeDate: cannot be in the future");
        }
        if (!calendar.isBusinessDay(tradeDate)) {
            throw new ValidationException("tradeDate: " + tradeDate + " is not a business day");
        }
        if (requestedSettleDate == null) {
            return calendar.addBusinessDays(tradeDate, settlementDays);
        }
        if (requestedSettleDate.isBefore(tradeDate)) {
            throw new ValidationException("settleDate: must be on or after tradeDate");
        }
        if (!calendar.isBusinessDay(requestedSettleDate)) {
            throw new ValidationException("settleDate: " + requestedSettleDate + " is not a business day");
        }
        return requestedSettleDate;
    }

    private static String rejectionReason(long portfolioId, PortfolioInfo portfolio, String isin,
                                          InstrumentInfo instrument) {
        if (portfolio == null) {
            return "Portfolio " + portfolioId + " does not exist";
        }
        if (!portfolio.isActive()) {
            return "Portfolio " + portfolioId + " is " + portfolio.status();
        }
        if (instrument == null) {
            return "No instrument with ISIN " + isin;
        }
        if (!instrument.isActive()) {
            return "Instrument " + isin + " is " + instrument.status();
        }
        return null;
    }

    private static boolean sameTrade(Trade t, CreateTradeRequest r, String isin, LocalDate tradeDate,
                                     LocalDate settleDate) {
        return Objects.equals(t.getPortfolioId(), r.portfolioId()) && t.getIsin().equals(isin)
                && t.getSide() == r.side() && t.getQuantity().compareTo(r.quantity()) == 0
                && t.getPrice().compareTo(r.price()) == 0 && t.getTradeDate().equals(tradeDate)
                && t.getSettleDate().equals(settleDate);
    }

    private void audit(Trade trade, TradeStatus from, Long userId, String comment) {
        audits.save(new TradeAudit(trade.getId(), from, trade.getStatus(), userId, clock.now(), comment));
    }

    private void publish(Trade trade, String eventType, String topic) {
        TradeEvent event = new TradeEvent(trade.getId(), trade.getTradeRef(), trade.getPortfolioId(),
                trade.getInstrumentId(), trade.getIsin(), trade.getSide().name(), trade.getQuantity(), trade.getPrice(),
                trade.getCurrency(), trade.getTradeDate(), trade.getSettleDate(), trade.getStatus().name());
        outbox.append("Trade", String.valueOf(trade.getId()), eventType, topic,
                String.valueOf(trade.getPortfolioId()), event);
    }

    private Trade find(long id) {
        return trades.findById(id).orElseThrow(() -> new NotFoundException("Trade " + id + " not found"));
    }
}
