package com.alphaledger.trade.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.alphaledger.common.error.BusinessRuleException;

/** Trade status state machine (FR-TR-3): NEW → VALIDATED → SETTLED, plus REJECTED and CANCELLED. */
class TradeLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-10-19T05:00:00Z");

    @Test
    void capturedTradeStartsAsNew() {
        assertThat(newTrade().getStatus()).isEqualTo(TradeStatus.NEW);
    }

    @Test
    void happyPathIsNewValidatedSettled() {
        Trade trade = newTrade();

        trade.validate(NOW);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.VALIDATED);

        trade.settle(NOW);
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.SETTLED);
    }

    @Test
    void newTradeCanBeRejectedWithAReason() {
        Trade trade = newTrade();

        trade.reject("SELL exceeds holding", NOW);

        assertThat(trade.getStatus()).isEqualTo(TradeStatus.REJECTED);
        assertThat(trade.getRejectionReason()).isEqualTo("SELL exceeds holding");
    }

    @Test
    void validatedTradeCanBeCancelled() {
        Trade trade = newTrade();
        trade.validate(NOW);

        trade.cancel(NOW);

        assertThat(trade.getStatus()).isEqualTo(TradeStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = TradeStatus.class, names = {"SETTLED", "REJECTED", "CANCELLED"})
    void terminalStatusesAllowNoFurtherTransitions(TradeStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (TradeStatus target : TradeStatus.values()) {
            assertThat(terminal.canTransitionTo(target)).as(terminal + " -> " + target).isFalse();
        }
    }

    @Test
    void invalidTransitionsAreRejectedWithClearMessages() {
        Trade settled = newTrade();
        settled.validate(NOW);
        settled.settle(NOW);

        assertThatThrownBy(() -> settled.cancel(NOW))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Trade TRD-1 cannot move from SETTLED to CANCELLED");
        assertThatThrownBy(() -> newTrade().settle(NOW))
                .hasMessage("Trade TRD-1 cannot move from NEW to SETTLED");
        assertThatThrownBy(() -> newTrade().cancel(NOW))
                .hasMessage("Trade TRD-1 cannot move from NEW to CANCELLED");
    }

    @Test
    void allowedTransitionsMatchTheSpec() {
        assertThat(TradeStatus.NEW.allowedNext()).isEqualTo(EnumSet.of(TradeStatus.VALIDATED, TradeStatus.REJECTED));
        assertThat(TradeStatus.VALIDATED.allowedNext())
                .isEqualTo(EnumSet.of(TradeStatus.SETTLED, TradeStatus.CANCELLED));
    }

    @Test
    void signedQuantityIsNegativeForSells() {
        assertThat(newTrade().signedQuantity()).isEqualByComparingTo("100");
        Trade sell = Trade.capture("TRD-2", 1L, 18L, "INE002A01018", TradeSide.SELL, new BigDecimal("40"),
                new BigDecimal("2500"), LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 21), 2L, NOW);
        assertThat(sell.signedQuantity()).isEqualByComparingTo("-40");
    }

    private static Trade newTrade() {
        return Trade.capture("TRD-1", 1L, 18L, "INE002A01018", TradeSide.BUY, new BigDecimal("100"),
                new BigDecimal("2500.50"), LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 21), 2L, NOW);
    }
}
