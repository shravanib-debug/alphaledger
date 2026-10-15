package com.alphaledger.common.calendar;

import java.time.LocalDate;

/** Business days for settlement and pricing (prd.md business rule 9): weekdays that are not exchange holidays. */
public interface BusinessDayCalendar {

    boolean isBusinessDay(LocalDate date);

    /**
     * The date {@code days} business days after {@code from}. Counting starts from the next business day, so a
     * Saturday trade plus one business day is the following Monday.
     */
    default LocalDate addBusinessDays(LocalDate from, int days) {
        LocalDate date = from;
        int added = 0;
        while (added < days) {
            date = date.plusDays(1);
            if (isBusinessDay(date)) {
                added++;
            }
        }
        return date;
    }

    /** The latest business day on or before {@code date} (the expected price date for staleness checks). */
    default LocalDate previousOrSameBusinessDay(LocalDate date) {
        LocalDate day = date;
        while (!isBusinessDay(day)) {
            day = day.minusDays(1);
        }
        return day;
    }
}
