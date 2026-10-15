package com.alphaledger.trade.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

/**
 * Weekends-only calendar used until the NSE holiday calendar lands (roadmap Phase 4A), which will replace
 * this bean with one backed by the {@code holiday_calendar} table.
 */
@Component
public class WeekendBusinessDayCalendar implements BusinessDayCalendar {

    @Override
    public boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
