package com.alphaledger.common.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Weekends plus the exchange's trading holidays. Holidays are published per calendar year; for a year with no
 * loaded holiday list only weekends are excluded, and a warning is logged once so the gap is visible.
 */
public final class HolidayCalendar implements BusinessDayCalendar {

    private static final Logger log = LoggerFactory.getLogger(HolidayCalendar.class);

    private final Set<LocalDate> holidays;
    private final Set<Integer> coveredYears;
    private final Set<Integer> warnedYears = ConcurrentHashMap.newKeySet();

    public HolidayCalendar(Collection<LocalDate> holidays) {
        this.holidays = Set.copyOf(holidays);
        this.coveredYears = holidays.stream().map(LocalDate::getYear).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static HolidayCalendar weekendsOnly() {
        return new HolidayCalendar(Set.of());
    }

    @Override
    public boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        if (!coveredYears.isEmpty() && !coveredYears.contains(date.getYear()) && warnedYears.add(date.getYear())) {
            log.warn("No exchange holiday list loaded for {}; treating every weekday as a business day",
                    date.getYear());
        }
        return !holidays.contains(date);
    }

    public boolean isHoliday(LocalDate date) {
        return holidays.contains(date);
    }

    public Set<Integer> coveredYears() {
        return coveredYears;
    }
}
