package com.alphaledger.valuation.marketdata;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.alphaledger.common.csv.CsvLine;
import com.alphaledger.valuation.marketdata.PriceBatch.PriceRecord;
import com.alphaledger.valuation.marketdata.PriceBatch.RejectedRow;

/**
 * NSE CM bhavcopy, UDiFF format ({@code BhavCopy_NSE_CM_0_0_0_yyyyMMdd_F_0000.csv}, architecture.md §5.8). The header is
 * checked first and the whole file fails if a needed column is missing. One price per ISIN: the closing price
 * {@code ClsPric}. Block-deal window rows (series {@code BL}) are ignored; if an ISIN still appears in several series,
 * the {@code EQ} row wins, otherwise the first row. Rows dated other than the file's date, and zero, blank or
 * non-numeric prices, are rejected with a reason. ISINs we do not hold are filtered later by the ingestion pipeline.
 */
public final class NseBhavcopyParser {

    static final List<String> REQUIRED = List.of("TradDt", "ISIN", "SctySrs", "ClsPric");
    private static final Set<String> IGNORED_SERIES = Set.of("BL");

    private NseBhavcopyParser() {
    }

    public static PriceBatch parse(String fileName, byte[] bytes, LocalDate dataDate) {
        String content = new String(bytes, StandardCharsets.UTF_8).replace("﻿", "");
        String[] lines = content.split("\r?\n");
        Map<String, Integer> col = header(lines.length == 0 ? "" : lines[0]);
        Map<String, Candidate> byIsin = new LinkedHashMap<>();
        List<RejectedRow> rejected = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            int row = i + 1;
            List<String> f = CsvLine.split(line);
            String isin = value(f, col.get("ISIN"));
            String series = value(f, col.get("SctySrs"));
            if (IGNORED_SERIES.contains(series)) {
                continue;
            }
            try {
                LocalDate tradeDate = LocalDate.parse(value(f, col.get("TradDt")));
                if (!tradeDate.equals(dataDate)) {
                    throw new IllegalArgumentException("TradDt " + tradeDate + " does not match file date " + dataDate);
                }
                String close = value(f, col.get("ClsPric"));
                if (close.isEmpty()) {
                    throw new IllegalArgumentException("blank ClsPric");
                }
                BigDecimal price = new BigDecimal(close);
                if (price.signum() <= 0) {
                    throw new IllegalArgumentException("ClsPric must be greater than zero");
                }
                Candidate existing = byIsin.get(isin);
                if (existing == null || (!"EQ".equals(existing.series) && "EQ".equals(series))) {
                    byIsin.put(isin, new Candidate(series, new PriceRecord(row, isin, dataDate,
                            MarketTime.marketClose(dataDate), price, PriceSourceName.NSE_BHAVCOPY, null)));
                }
            } catch (RuntimeException ex) {
                rejected.add(new RejectedRow(row, isin.length() <= 12 ? isin : null, ex.getMessage(),
                        line.length() > 1000 ? line.substring(0, 1000) : line));
            }
        }
        List<PriceRecord> records = byIsin.values().stream().map(Candidate::record).toList();
        return new PriceBatch(PriceSourceName.NSE_BHAVCOPY, dataDate, fileName, MarketTime.sha256(bytes), records,
                rejected, false, false);
    }

    private record Candidate(String series, PriceRecord record) {
    }

    private static Map<String, Integer> header(String line) {
        List<String> names = CsvLine.split(line.replace("﻿", ""));
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            col.put(names.get(i).trim(), i);
        }
        List<String> missing = REQUIRED.stream().filter(c -> !col.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Bhavcopy header changed: missing column(s) " + missing
                    + "; the whole file is refused");
        }
        return col;
    }

    private static String value(List<String> fields, int index) {
        return index < fields.size() ? fields.get(index).trim() : "";
    }
}
