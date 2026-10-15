package com.alphaledger.common.instrument;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;

import com.alphaledger.common.csv.CsvLine;

/**
 * Reads the committed instrument master ({@code reference-data/instruments/instrument_master.csv}): the single source
 * of instrument IDs shared by every service (architecture.md §5.8). Enum-like columns are returned as text; each
 * service maps them to its own types.
 */
public final class InstrumentMaster {

    public static final String RESOURCE = "reference-data/instruments/instrument_master.csv";
    public static final String HEADER = "instrument_id,isin,symbol,exchange,name,instrument_type,asset_class,"
            + "asset_class_proxy,amfi_scheme_code,is_synthetic";

    private InstrumentMaster() {
    }

    public record Row(long instrumentId, String isin, String symbol, String exchange, String name,
                      String instrumentType, String assetClass, String assetClassProxy, String amfiSchemeCode,
                      boolean synthetic) {
    }

    public static List<Row> readCommitted() throws IOException {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return read(in);
        }
    }

    /** Parses and checks structure, unique IDs and ISINs, and ISIN validity (real) or prefix (synthetic). */
    public static List<Row> read(InputStream in) throws IOException {
        List<Row> rows = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        Set<String> isins = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (!HEADER.equals(header)) {
                throw new IllegalStateException("Unexpected instrument master header: " + header);
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                List<String> f = CsvLine.split(line);
                if (f.size() != 10) {
                    throw new IllegalStateException("Line " + lineNumber + ": expected 10 fields, found " + f.size());
                }
                Row row;
                try {
                    row = new Row(Long.parseLong(f.get(0)), f.get(1), blankToNull(f.get(2)), blankToNull(f.get(3)),
                            f.get(4), f.get(5), f.get(6), f.get(7), blankToNull(f.get(8)), Boolean.parseBoolean(f.get(9)));
                } catch (NumberFormatException ex) {
                    throw new IllegalStateException("Line " + lineNumber + ": invalid instrument_id", ex);
                }
                boolean identityOk = row.synthetic() ? Isin.isSynthetic(row.isin()) && Isin.hasValidFormat(row.isin())
                        : Isin.isValid(row.isin());
                if (!identityOk) {
                    throw new IllegalStateException("Line " + lineNumber + ": invalid ISIN or check digit " + row.isin());
                }
                if (!ids.add(row.instrumentId()) || !isins.add(row.isin())) {
                    throw new IllegalStateException("Duplicate instrument_id or ISIN on line " + lineNumber);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }
}
