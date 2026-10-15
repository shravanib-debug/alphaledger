package com.alphaledger.common.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

/** T+1 settlement on the committed 2026 NSE CM holiday list. */
class HolidayCalendarTest {

    private static HolidayCalendar calendar;

    @BeforeAll
    static void loadCommittedHolidayFile() throws Exception {
        try (InputStream in = new ClassPathResource("reference-data/holidays/nse_cm_holidays_2026.csv").getInputStream()) {
            List<HolidayCalendarFile.Holiday> holidays = HolidayCalendarFile.read(in);
            assertThat(holidays).hasSize(20);
            calendar = new HolidayCalendar(holidays.stream().map(HolidayCalendarFile.Holiday::date).toList());
        }
    }

    @ParameterizedTest(name = "trade {0} settles {1}")
    @CsvSource({
            "2026-10-19, 2026-10-21",   // Monday; Tuesday 20-Oct is Dussehra
            "2026-10-16, 2026-10-19",   // Friday -> Monday
            "2026-11-09, 2026-11-11",   // Monday; Tuesday 10-Nov is Diwali-Balipratipada
            "2026-10-01, 2026-10-05",   // Thursday; Friday 02-Oct is Gandhi Jayanti, then the weekend
            "2026-12-24, 2026-12-28",   // Thursday; Friday 25-Dec is Christmas
            "2026-10-21, 2026-10-22"    // ordinary Wednesday
    })
    void tPlusOneSkipsWeekendsAndNseHolidays(LocalDate tradeDate, LocalDate settleDate) {
        assertThat(calendar.addBusinessDays(tradeDate, 1)).isEqualTo(settleDate);
    }

    @Test
    void holidaysAndWeekendsAreNotBusinessDays() {
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 10, 20))).isFalse();   // Dussehra
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 1, 15))).isFalse();    // Maharashtra elections
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 10, 24))).isFalse();   // Saturday
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 10, 21))).isTrue();
    }

    @Test
    void previousBusinessDayIsTheExpectedPriceDate() {
        assertThat(calendar.previousOrSameBusinessDay(LocalDate.of(2026, 10, 20))).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(calendar.previousOrSameBusinessDay(LocalDate.of(2026, 10, 25))).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(calendar.previousOrSameBusinessDay(LocalDate.of(2026, 10, 21))).isEqualTo(LocalDate.of(2026, 10, 21));
    }

    @Test
    void yearsWithoutAHolidayListFallBackToWeekendsOnly() {
        assertThat(calendar.coveredYears()).containsExactly(2026);
        assertThat(calendar.isBusinessDay(LocalDate.of(2027, 1, 26))).isTrue();     // 2027 list not loaded yet
        assertThat(HolidayCalendar.weekendsOnly().isBusinessDay(LocalDate.of(2026, 10, 20))).isTrue();
    }
}
