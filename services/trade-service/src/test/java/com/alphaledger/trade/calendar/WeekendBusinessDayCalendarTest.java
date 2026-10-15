package com.alphaledger.trade.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** T+1 settlement on a weekends-only calendar; NSE holidays plug in during Phase 4A. */
class WeekendBusinessDayCalendarTest {

    private final BusinessDayCalendar calendar = new WeekendBusinessDayCalendar();

    @ParameterizedTest(name = "trade {0} settles {1}")
    @CsvSource({
            "2026-10-19, 2026-10-20",   // Monday -> Tuesday
            "2026-10-22, 2026-10-23",   // Thursday -> Friday
            "2026-10-23, 2026-10-26",   // Friday -> Monday
            "2026-10-24, 2026-10-26",   // Saturday (not a trading day) -> Monday
    })
    void tPlusOneSkipsWeekends(LocalDate tradeDate, LocalDate expectedSettle) {
        assertThat(calendar.addBusinessDays(tradeDate, 1)).isEqualTo(expectedSettle);
    }

    @ParameterizedTest
    @CsvSource({"2026-10-19, true", "2026-10-23, true", "2026-10-24, false", "2026-10-25, false"})
    void weekendsAreNotBusinessDays(LocalDate date, boolean businessDay) {
        assertThat(calendar.isBusinessDay(date)).isEqualTo(businessDay);
    }

    @ParameterizedTest
    @CsvSource({"2026-10-23, 2, 2026-10-27", "2026-10-19, 0, 2026-10-19"})
    void addsAnyNumberOfBusinessDays(LocalDate from, int days, LocalDate expected) {
        assertThat(calendar.addBusinessDays(from, days)).isEqualTo(expected);
    }
}
