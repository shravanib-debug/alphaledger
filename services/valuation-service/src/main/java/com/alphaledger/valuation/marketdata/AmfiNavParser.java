package com.alphaledger.valuation.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;
import java.util.Set;

import com.alphaledger.valuation.marketdata.PriceBatch.PriceRecord;
import com.alphaledger.valuation.marketdata.PriceBatch.RejectedRow;

/**
 * One line of AMFI {@code NAVAll.txt} (architecture.md §5.8): semicolon-separated
 * {@code Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;Net Asset Value;Date}.
 * Scheme-category headings, fund-house names and blank lines are skipped. A row matches a held fund by either ISIN
 * column; rows for other funds are skipped. A zero or non-numeric NAV ({@code N.A.}) is rejected with a reason.
 */
public final class AmfiNavParser {

    static final String HEADER_PREFIX = "Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name";
    private static final int NAV_COLUMN_FROM_END = 2;   // ...;Net Asset Value;Date
    private static final DateTimeFormatter DATE = new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH);

    private AmfiNavParser() {
    }

    /** The result for one line: a price, a rejected row, or nothing (skipped). */
    public sealed interface Line permits Price, Rejected, Skipped {
    }

    public record Price(PriceRecord record) implements Line {
    }

    public record Rejected(RejectedRow row) implements Line {
    }

    public record Skipped() implements Line {
    }

    private static final Skipped SKIPPED = new Skipped();

    /** Fails the whole file when the header is not the expected one. */
    public static void checkHeader(String firstLine) {
        String header = firstLine == null ? "" : firstLine.replace("﻿", "").strip();
        if (!header.startsWith(HEADER_PREFIX) || header.split(";", -1).length < 6) {
            throw new IllegalStateException("NAVAll.txt header changed: '" + header + "'; the whole file is refused");
        }
    }

    public static Line parse(int rowNumber, String line, Set<String> wantedIsins) {
        String[] f = line.split(";", -1);
        if (f.length < 6) {
            return SKIPPED;   // category heading, fund-house name or blank line
        }
        String isin = matching(f[1].trim(), f[2].trim(), wantedIsins);
        if (isin == null) {
            return SKIPPED;
        }
        String navText = f[f.length - NAV_COLUMN_FROM_END].trim();
        try {
            LocalDate date = LocalDate.parse(f[f.length - 1].trim(), DATE);
            BigDecimal nav;
            try {
                nav = new BigDecimal(navText);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("NAV '" + navText + "' is not a number");
            }
            if (nav.signum() <= 0) {
                throw new IllegalArgumentException("NAV must be greater than zero");
            }
            return new Price(new PriceRecord(rowNumber, isin, date, MarketTime.marketClose(date), nav,
                    PriceSourceName.AMFI_NAV, null));
        } catch (RuntimeException ex) {
            return new Rejected(new RejectedRow(rowNumber, isin, ex.getMessage(),
                    line.length() > 1000 ? line.substring(0, 1000) : line));
        }
    }

    private static String matching(String first, String second, Set<String> wanted) {
        if (wanted.contains(first)) {
            return first;
        }
        return wanted.contains(second) ? second : null;
    }
}
