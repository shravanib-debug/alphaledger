package com.alphaledger.trade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import com.alphaledger.common.error.ConflictException;
import com.alphaledger.common.error.ValidationException;
import com.alphaledger.common.event.Topics;
import com.alphaledger.common.security.AuthenticatedUser;
import com.alphaledger.common.calendar.MarketClock;
import com.alphaledger.common.calendar.HolidayCalendar;
import com.alphaledger.trade.client.PortfolioClient;
import com.alphaledger.trade.client.PortfolioClient.InstrumentInfo;
import com.alphaledger.trade.client.PortfolioClient.PortfolioInfo;
import com.alphaledger.trade.config.TradeProperties;
import com.alphaledger.trade.domain.Trade;
import com.alphaledger.trade.domain.TradeSide;
import com.alphaledger.trade.domain.TradeStatus;
import com.alphaledger.trade.dto.CreateTradeRequest;
import com.alphaledger.common.outbox.OutboxWriter;
import com.alphaledger.trade.repository.TradeAuditRepository;
import com.alphaledger.trade.repository.TradeRepository;

/** Validation and business rules of trade capture (FR-TR-2), with collaborators mocked. */
class TradeServiceTest {

    // Wednesday 21-Oct-2026, 11:30 IST
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T06:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final AuthenticatedUser PRIYA = new AuthenticatedUser(2, "priya@x", Set.of("PM"));
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1, "anita@x", Set.of("ADMIN"));
    private static final String RELIANCE = "INE002A01018";

    private final TradeRepository trades = mock(TradeRepository.class);
    private final TradeAuditRepository audits = mock(TradeAuditRepository.class);
    private final PortfolioClient portfolios = mock(PortfolioClient.class);
    private final HoldingGuard holdings = mock(HoldingGuard.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final TradeRefGenerator refs = mock(TradeRefGenerator.class);
    private final AtomicLong ids = new AtomicLong(100);
    private TradeService service;

    @BeforeEach
    void setUp() {
        service = new TradeService(trades, audits, portfolios, new HolidayCalendar(java.util.List.of(LocalDate.of(2026, 10, 20))),
                new MarketClock(CLOCK, ZoneId.of("Asia/Kolkata")), holdings, outbox, refs,
                new TradeProperties(null, 1, 1));
        when(trades.save(any())).thenAnswer(invocation -> {
            Trade trade = invocation.getArgument(0);
            ReflectionTestUtils.setField(trade, "id", ids.incrementAndGet());
            return trade;
        });
        when(refs.next(any())).thenReturn("TRD-20261021-0001");
        when(portfolios.findPortfolio(1L)).thenReturn(Optional.of(new PortfolioInfo(1, "Credit", 2, "ACTIVE")));
        when(portfolios.findPortfolio(3L)).thenReturn(Optional.of(new PortfolioInfo(3, "Other", 99, "ACTIVE")));
        when(portfolios.findPortfolio(5L)).thenReturn(Optional.of(new PortfolioInfo(5, "Old", 2, "ARCHIVED")));
        when(portfolios.findInstrumentByIsin(RELIANCE))
                .thenReturn(Optional.of(new InstrumentInfo(18, RELIANCE, "Reliance", "EQUITY", "ACTIVE")));
    }

    @Test
    void validBuyIsValidatedWithTPlusOneSettlementAndPublishesTradeCreated() {
        TradeService.CreateResult result = service.create(request(null, 1L, TradeSide.BUY, "100", null, null), PRIYA);

        assertThat(result.replayed()).isFalse();
        assertThat(result.trade().status()).isEqualTo(TradeStatus.VALIDATED);
        assertThat(result.trade().tradeDate()).isEqualTo(TODAY);
        assertThat(result.trade().settleDate()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(result.trade().tradeRef()).isEqualTo("TRD-20261021-0001");
        assertThat(result.trade().notional()).isEqualByComparingTo("250050");
        verify(outbox).append(eq("Trade"), anyString(), eq("TRADE_CREATED"), eq(Topics.TRADE_CREATED), eq("1"), any());
    }

    @Test
    void sellAboveTheHoldingIsStoredAsRejectedAndNotPublished() {
        when(holdings.lockAndGet(eq(1L), eq(18L), anyLong())).thenReturn(new BigDecimal("30"));

        TradeService.CreateResult result = service.create(request(null, 1L, TradeSide.SELL, "40", null, null), PRIYA);

        assertThat(result.trade().status()).isEqualTo(TradeStatus.REJECTED);
        assertThat(result.trade().rejectionReason()).isEqualTo("SELL of 40 exceeds the current holding of 30");
        verify(outbox, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    void unknownIsinAndArchivedPortfolioAreRejectedWithReasons() {
        assertThat(service.create(new CreateTradeRequest(null, 1L, "INE154A01025", TradeSide.BUY, BigDecimal.TEN,
                BigDecimal.ONE, null, null), PRIYA).trade().rejectionReason())
                .isEqualTo("No instrument with ISIN INE154A01025");
        assertThat(service.create(request(null, 5L, TradeSide.BUY, "10", null, null), PRIYA).trade()
                .rejectionReason()).isEqualTo("Portfolio 5 is ARCHIVED");
        assertThat(service.create(request(null, 77L, TradeSide.BUY, "10", null, null), ADMIN).trade()
                .rejectionReason()).isEqualTo("Portfolio 77 does not exist");
    }

    @Test
    void pmCannotTradeForAPortfolioTheyDoNotManage() {
        assertThatThrownBy(() -> service.create(request(null, 3L, TradeSide.BUY, "10", null, null), PRIYA))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(service.create(request(null, 3L, TradeSide.BUY, "10", null, null), ADMIN).trade().status())
                .isEqualTo(TradeStatus.VALIDATED);
    }

    @Test
    void sameTradeRefWithSameDetailsReplaysAndWithOtherDetailsConflicts() {
        Trade existing = Trade.capture("CLIENT-1", 1L, 18L, RELIANCE, TradeSide.BUY, new BigDecimal("100"),
                new BigDecimal("2500.50"), TODAY, LocalDate.of(2026, 10, 22), 2L, CLOCK.instant());
        when(trades.findByTradeRef("CLIENT-1")).thenReturn(Optional.of(existing));

        assertThat(service.create(request("CLIENT-1", 1L, TradeSide.BUY, "100", null, null), PRIYA).replayed())
                .isTrue();
        assertThatThrownBy(() -> service.create(request("CLIENT-1", 1L, TradeSide.BUY, "101", null, null), PRIYA))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void datesAreValidatedAgainstTheBusinessCalendar() {
        assertThatThrownBy(() -> service.create(request(null, 1L, TradeSide.BUY, "1", LocalDate.of(2026, 10, 22), null),
                PRIYA)).isInstanceOf(ValidationException.class).hasMessage("tradeDate: cannot be in the future");
        assertThatThrownBy(() -> service.create(request(null, 1L, TradeSide.BUY, "1", LocalDate.of(2026, 10, 18), null),
                PRIYA)).hasMessage("tradeDate: 2026-10-18 is not a business day");
        assertThatThrownBy(() -> service.create(request(null, 1L, TradeSide.BUY, "1", TODAY, LocalDate.of(2026, 10, 20)),
                PRIYA)).hasMessage("settleDate: must be on or after tradeDate");
        assertThatThrownBy(() -> service.create(request(null, 1L, TradeSide.BUY, "1", TODAY, LocalDate.of(2026, 10, 24)),
                PRIYA)).hasMessage("settleDate: 2026-10-24 is not a business day");
    }

    @Test
    void settlementSkipsNseHolidays() {
        // Monday 19-Oct-2026: Tuesday 20-Oct is Dussehra, so T+1 is Wednesday.
        assertThat(service.resolveSettleDate(LocalDate.of(2026, 10, 19), null)).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThatThrownBy(() -> service.resolveSettleDate(LocalDate.of(2026, 10, 20), null))
                .hasMessage("tradeDate: 2026-10-20 is not a business day");
        assertThatThrownBy(() -> service.resolveSettleDate(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20)))
                .hasMessage("settleDate: 2026-10-20 is not a business day");
    }

    @Test
    void fridayTradeSettlesOnMonday() {
        assertThat(service.resolveSettleDate(LocalDate.of(2026, 10, 16), null)).isEqualTo(LocalDate.of(2026, 10, 19));
    }

    @Test
    void tradeRefFormat() {
        assertThat(TradeRefGenerator.format(LocalDate.of(2026, 10, 19), 7)).isEqualTo("TRD-20261019-0007");
        assertThat(TradeRefGenerator.format(LocalDate.of(2026, 10, 19), 12345)).isEqualTo("TRD-20261019-12345");
    }

    private static CreateTradeRequest request(String tradeRef, Long portfolioId, TradeSide side, String quantity,
                                              LocalDate tradeDate, LocalDate settleDate) {
        return new CreateTradeRequest(tradeRef, portfolioId, RELIANCE, side, new BigDecimal(quantity),
                new BigDecimal("2500.50"), tradeDate, settleDate);
    }
}
