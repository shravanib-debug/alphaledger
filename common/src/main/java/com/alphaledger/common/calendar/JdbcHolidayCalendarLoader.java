package com.alphaledger.common.calendar;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Loads every committed {@code reference-data/holidays/*.csv} into the service's own {@code holiday_calendar} table
 * (database per service), then builds the calendar from that table. Re-running is safe.
 */
public final class JdbcHolidayCalendarLoader {

    public static final String EXCHANGE = "NSE";
    public static final String SEGMENT = "CM";
    private static final String PATTERN = "classpath*:reference-data/holidays/*.csv";
    private static final Logger log = LoggerFactory.getLogger(JdbcHolidayCalendarLoader.class);

    private JdbcHolidayCalendarLoader() {
    }

    public static HolidayCalendar load(JdbcTemplate jdbc) {
        int files = 0;
        for (Resource resource : resources()) {
            files++;
            try (InputStream in = resource.getInputStream()) {
                for (HolidayCalendarFile.Holiday h : HolidayCalendarFile.read(in)) {
                    jdbc.update("""
                            INSERT INTO holiday_calendar (holiday_date, exchange, segment, description)
                            VALUES (?, ?, ?, ?)
                            ON CONFLICT (holiday_date, exchange, segment) DO UPDATE SET description = EXCLUDED.description
                            """, Date.valueOf(h.date()), h.exchange(), h.segment(), h.description());
                }
            } catch (IOException ex) {
                throw new UncheckedIOException("Cannot read holiday file " + resource, ex);
            }
        }
        List<java.time.LocalDate> holidays = new ArrayList<>(jdbc.queryForList(
                "SELECT holiday_date FROM holiday_calendar WHERE exchange = ? AND segment = ?",
                java.time.LocalDate.class, EXCHANGE, SEGMENT));
        HolidayCalendar calendar = new HolidayCalendar(holidays);
        log.info("Holiday calendar: {} {} {} holidays from {} file(s), years {}", holidays.size(), EXCHANGE, SEGMENT,
                files, calendar.coveredYears());
        return calendar;
    }

    private static Resource[] resources() {
        try {
            return new PathMatchingResourcePatternResolver().getResources(PATTERN);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
