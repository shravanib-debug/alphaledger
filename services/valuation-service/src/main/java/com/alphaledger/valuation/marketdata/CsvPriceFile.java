package com.alphaledger.valuation.marketdata;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.alphaledger.common.csv.CsvLine;
import com.alphaledger.common.error.ValidationException;
import com.alphaledger.common.instrument.Isin;
import com.alphaledger.valuation.marketdata.PriceBatch.PriceRecord;
import com.alphaledger.valuation.marketdata.PriceBatch.RejectedRow;

/**
 * Manual price upload and the committed seed file: {@code isin,data_date,price[,source]}. {@code source} defaults to
 * CSV_UPLOAD; a row may instead declare the real provenance of a hand-copied price (NSE_BHAVCOPY, AMFI_NAV, YFINANCE).
 * SYNTHETIC and MFAPI cannot be declared. Unknown ISINs are reported as rejected rows.
 */
public final class CsvPriceFile {

    static final String HEADER = "isin,data_date,price";
    static final String HEADER_WITH_SOURCE = "isin,data_date,price,source";
    static final int MAX_ROWS = 10_000;

    private CsvPriceFile() {
    }

    public static PriceBatch parse(String fileName, byte[] bytes) {
        String content = new String(bytes, StandardCharsets.UTF_8).replace("﻿", "");
        List<PriceRecord> records = new ArrayList<>();
        List<RejectedRow> rejected = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String header = reader.readLine();
            header = header == null ? "" : header.strip().toLowerCase(Locale.ROOT);
            boolean withSource = HEADER_WITH_SOURCE.equals(header);
            if (!withSource && !HEADER.equals(header)) {
                throw new ValidationException("file: first line must be '" + HEADER + "' or '" + HEADER_WITH_SOURCE + "'");
            }
            String line;
            int row = 1;
            while ((line = reader.readLine()) != null) {
                row++;
                if (line.isBlank()) {
                    continue;
                }
                if (records.size() + rejected.size() >= MAX_ROWS) {
                    throw new ValidationException("file: at most " + MAX_ROWS + " rows per upload");
                }
                List<String> f = CsvLine.split(line);
                String isin = f.isEmpty() ? null : Isin.normalize(f.get(0));
                try {
                    if (f.size() != (withSource ? 4 : 3)) {
                        throw new IllegalArgumentException("expected " + (withSource ? 4 : 3) + " columns");
                    }
                    if (!Isin.hasValidFormat(isin)) {
                        throw new IllegalArgumentException("invalid ISIN");
                    }
                    LocalDate date = LocalDate.parse(f.get(1).trim());
                    PriceSourceName source = withSource && !f.get(3).isBlank()
                            ? PriceSourceName.valueOf(f.get(3).trim().toUpperCase(Locale.ROOT))
                            : PriceSourceName.CSV_UPLOAD;
                    if (!source.allowedInCsv()) {
                        throw new IllegalArgumentException("source " + source + " cannot be uploaded");
                    }
                    records.add(new PriceRecord(row, isin, date, MarketTime.marketClose(date),
                            new BigDecimal(f.get(2).trim()), source, null));
                } catch (RuntimeException ex) {
                    rejected.add(new RejectedRow(row, isin != null && isin.length() <= 12 ? isin : null,
                            ex.getMessage(), line.length() > 1000 ? line.substring(0, 1000) : line));
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new PriceBatch(PriceSourceName.CSV_UPLOAD, null, fileName, MarketTime.sha256(bytes), records, rejected,
                true, false);
    }
}
