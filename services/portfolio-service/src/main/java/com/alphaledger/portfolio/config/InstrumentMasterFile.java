package com.alphaledger.portfolio.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.alphaledger.portfolio.domain.AssetClass;
import com.alphaledger.portfolio.domain.AssetClassProxy;
import com.alphaledger.portfolio.domain.InstrumentType;
import com.alphaledger.portfolio.service.InstrumentService;

/**
 * Reads {@code reference-data/instruments/instrument_master.csv}: the single source of instrument IDs shared by
 * all services (architecture.md §5.8). Every row is validated; any bad row fails the whole file.
 */
public final class InstrumentMasterFile {

    static final String HEADER = "instrument_id,isin,symbol,exchange,name,instrument_type,asset_class,"
            + "asset_class_proxy,amfi_scheme_code,is_synthetic";

    private InstrumentMasterFile() {
    }

    public record Row(long instrumentId, String isin, String symbol, String exchange, String name,
                      InstrumentType instrumentType, AssetClass assetClass, AssetClassProxy assetClassProxy,
                      String amfiSchemeCode, boolean synthetic) {
    }

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
                Row row = parse(line, lineNumber);
                if (!ids.add(row.instrumentId()) || !isins.add(row.isin())) {
                    throw new IllegalStateException("Duplicate instrument_id or ISIN on line " + lineNumber);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static Row parse(String line, int lineNumber) {
        List<String> f = splitCsv(line);
        if (f.size() != 10) {
            throw new IllegalStateException("Line " + lineNumber + ": expected 10 fields, found " + f.size());
        }
        try {
            Row row = new Row(Long.parseLong(f.get(0)), f.get(1), emptyToNull(f.get(2)), emptyToNull(f.get(3)),
                    f.get(4), InstrumentType.valueOf(f.get(5)), AssetClass.valueOf(f.get(6)),
                    AssetClassProxy.valueOf(f.get(7)), emptyToNull(f.get(8)), Boolean.parseBoolean(f.get(9)));
            InstrumentService.validateIdentity(row.isin(), row.synthetic(), row.instrumentType(),
                    row.assetClassProxy());
            return row;
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Line " + lineNumber + " is invalid: " + ex.getMessage(), ex);
        }
    }

    /** Minimal RFC 4180 split: commas inside double quotes are kept; "" is an escaped quote. */
    static List<String> splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static String emptyToNull(String value) {
        return value.isBlank() ? null : value;
    }
}
