package com.alphaledger.trade.calendar;

import java.time.LocalDate;

/** Business days for settlement (prd.md business rule 9): weekdays that are not exchange holidays. */
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
}
