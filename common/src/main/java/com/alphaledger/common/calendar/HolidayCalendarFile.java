package com.alphaledger.common.calendar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads {@code reference-data/holidays/nse_cm_holidays_<year>.csv}: {@code holiday_date,exchange,segment,description}.
 * Copied each year from NSE's published trading-holiday list (architecture.md §5.8).
 */
public final class HolidayCalendarFile {

    static final String HEADER = "holiday_date,exchange,segment,description";

    private HolidayCalendarFile() {
    }

    public record Holiday(LocalDate date, String exchange, String segment, String description) {
    }

    public static List<Holiday> read(InputStream in) throws IOException {
        List<Holiday> holidays = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (!HEADER.equals(header)) {
                throw new IllegalStateException("Unexpected holiday file header: " + header);
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split(",", 4);
                if (f.length != 4) {
                    throw new IllegalStateException("Line " + lineNumber + ": expected 4 fields");
                }
                try {
                    Holiday holiday = new Holiday(LocalDate.parse(f[0].trim()), f[1].trim(), f[2].trim(), f[3].trim());
                    if (!seen.add(holiday.date() + "|" + holiday.exchange() + "|" + holiday.segment())) {
                        throw new IllegalStateException("Line " + lineNumber + ": duplicate holiday " + holiday.date());
                    }
                    holidays.add(holiday);
                } catch (DateTimeParseException ex) {
                    throw new IllegalStateException("Line " + lineNumber + ": invalid date '" + f[0] + "'", ex);
                }
            }
        }
        return holidays;
    }
}
