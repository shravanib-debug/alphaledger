package com.alphaledger.valuation.nav;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.error.BusinessRuleException;
import com.alphaledger.common.error.NotFoundException;
import com.alphaledger.common.event.EventEnvelope;
import com.alphaledger.common.event.ProcessedEvents;
import com.alphaledger.common.event.TradeEvent;
import com.alphaledger.valuation.valuation.NavCalculator;

/**
 * Cash and liabilities (FR-VAL-8, FR-VAL-9). Every cash movement is a ledger entry; the balance follows it in the same
 * transaction. Settled trades move cash on their settle date; a borrowing brings its cash in when it is recorded, and
 * paying any liability takes its amount out.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerRepository repository;
    private final ProcessedEvents processedEvents;
    private final MarketClock clock;

    public LedgerService(LedgerRepository repository, ProcessedEvents processedEvents, MarketClock clock) {
        this.repository = repository;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    @Transactional
    public void onTradeSettled(EventEnvelope<TradeEvent> event) {
        if (!processedEvents.markProcessed(event.eventId())) {
            return;
        }
        TradeEvent t = event.payload();
        BigDecimal amount = NavCalculator.settlementCash(t.side(), t.quantity(), t.price());
        String type = "BUY".equals(t.side()) ? "TRADE_BUY" : "TRADE_SELL";
        boolean recorded = repository.appendCash(t.portfolioId(), type, amount, t.tradeRef(),
                t.side() + " " + t.quantity().stripTrailingZeros().toPlainString() + " " + t.isin() + " @ "
                        + t.price().stripTrailingZeros().toPlainString(),
                t.settleDate(), null, clock.now());
        if (!recorded) {
            log.info("Settlement of trade {} already recorded; skipped", t.tradeRef());
        }
    }

    public record CashSummary(long portfolioId, String currency, BigDecimal balance,
                              List<Map<String, Object>> transactions) {
    }

    @Transactional(readOnly = true)
    public CashSummary cash(long portfolioId) {
        return new CashSummary(portfolioId, "INR", repository.balance(portfolioId, false),
                repository.cashTransactions(portfolioId));
    }

    /** A deposit (positive) or withdrawal; a withdrawal may not take the balance below zero. */
    @Transactional
    public CashSummary moveCash(long portfolioId, String type, BigDecimal amount, String description,
                                LocalDate effectiveDate, long userId) {
        LocalDate date = effectiveDate == null ? clock.today() : effectiveDate;
        BigDecimal signed = amount;
        if ("WITHDRAWAL".equals(type)) {
            BigDecimal balance = repository.balance(portfolioId, true);
            if (balance.compareTo(amount) < 0) {
                throw new BusinessRuleException("Withdrawal of " + amount + " exceeds the cash balance of " + balance);
            }
            signed = amount.negate();
        }
        repository.appendCash(portfolioId, type, signed, null, description, date, userId, clock.now());
        return cash(portfolioId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> liabilities(long portfolioId, String status) {
        return repository.liabilities(portfolioId, status);
    }

    @Transactional
    public Map<String, Object> addLiability(long portfolioId, String type, String description, BigDecimal amount,
                                            LocalDate accrualDate, long userId) {
        LocalDate date = accrualDate == null ? clock.today() : accrualDate;
        Map<String, Object> row = repository.insertLiability(portfolioId, type, description, amount, date, clock.now());
        if ("BORROWING".equals(type)) {
            repository.appendCash(portfolioId, "BORROWING", amount, "LIABILITY-" + row.get("id"),
                    "Borrowed: " + description, date, userId, clock.now());
        }
        return row;
    }

    /** Corrects an open liability. A borrowing's amount is fixed because its cash already came in. */
    @Transactional
    public Map<String, Object> updateLiability(long portfolioId, long id, String description, BigDecimal amount,
                                               LocalDate accrualDate) {
        Map<String, Object> current = openLiability(portfolioId, id);
        if ("BORROWING".equals(current.get("type")) && ((BigDecimal) current.get("amount")).compareTo(amount) != 0) {
            throw new BusinessRuleException("A borrowing's amount cannot change; pay it and record a new one");
        }
        LocalDate date = accrualDate == null ? ((Date) current.get("accrual_date")).toLocalDate() : accrualDate;
        return repository.updateLiability(id, description, amount, date, clock.now());
    }

    /** Pays an open liability out of cash on {@code paidDate} (default today). */
    @Transactional
    public Map<String, Object> payLiability(long portfolioId, long id, LocalDate paidDate, long userId) {
        Map<String, Object> current = openLiability(portfolioId, id);
        LocalDate date = paidDate == null ? clock.today() : paidDate;
        LocalDate accrued = ((Date) current.get("accrual_date")).toLocalDate();
        if (date.isBefore(accrued)) {
            throw new BusinessRuleException("A liability cannot be paid before its accrual date " + accrued);
        }
        repository.appendCash(portfolioId, "LIABILITY_PAYMENT", ((BigDecimal) current.get("amount")).negate(),
                "LIABILITY-" + id, "Paid: " + current.get("description"), date, userId, clock.now());
        return repository.markPaid(id, date, clock.now());
    }

    /** Removes a liability recorded in error. Borrowings are repaid, not deleted, to keep the ledger balanced. */
    @Transactional
    public void deleteLiability(long portfolioId, long id) {
        Map<String, Object> current = openLiability(portfolioId, id);
        if ("BORROWING".equals(current.get("type"))) {
            throw new BusinessRuleException("A borrowing cannot be deleted; pay it instead");
        }
        repository.deleteLiability(id);
    }

    private Map<String, Object> openLiability(long portfolioId, long id) {
        Map<String, Object> row = repository.liabilityForUpdate(portfolioId, id)
                .orElseThrow(() -> new NotFoundException("Liability " + id + " not found in portfolio " + portfolioId));
        if (!"OPEN".equals(row.get("status"))) {
            throw new BusinessRuleException("Liability " + id + " is already paid");
        }
        return row;
    }
}
