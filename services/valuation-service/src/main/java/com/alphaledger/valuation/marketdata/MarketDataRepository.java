package com.alphaledger.valuation.marketdata;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.alphaledger.valuation.marketdata.PriceBatch.PriceRecord;
import com.alphaledger.valuation.marketdata.PriceBatch.RejectedRow;

/** SQL for instruments, prices and the ingestion log (JDBC: the upserts rely on PostgreSQL partial indexes). */
@Repository
public class MarketDataRepository {

    // Ties on the same date and timestamp go to real exchange data first, synthetic last.
    private static final String SOURCE_PRECEDENCE = """
            CASE p.source WHEN 'NSE_BHAVCOPY' THEN 1 WHEN 'AMFI_NAV' THEN 2 WHEN 'CSV_UPLOAD' THEN 3
                          WHEN 'MFAPI' THEN 4 WHEN 'YFINANCE' THEN 5 ELSE 6 END""";

    private static final RowMapper<InstrumentRef> INSTRUMENT = (rs, i) -> new InstrumentRef(rs.getLong("instrument_id"),
            rs.getString("isin"), rs.getString("symbol"), rs.getString("name"), rs.getString("instrument_type"),
            rs.getString("asset_class"), rs.getBoolean("is_synthetic"), rs.getString("amfi_scheme_code"));

    private final JdbcTemplate jdbc;

    public MarketDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<InstrumentRef> instruments() {
        return jdbc.query("SELECT * FROM instrument_ref ORDER BY instrument_id", INSTRUMENT);
    }

    public Map<String, InstrumentRef> instrumentsByIsin() {
        return instruments().stream().collect(Collectors.toMap(InstrumentRef::isin, Function.identity()));
    }

    public Optional<InstrumentRef> instrument(Long instrumentId, String isin) {
        return instrumentId != null
                ? jdbc.query("SELECT * FROM instrument_ref WHERE instrument_id = ?", INSTRUMENT, instrumentId).stream().findFirst()
                : jdbc.query("SELECT * FROM instrument_ref WHERE isin = ?", INSTRUMENT, isin).stream().findFirst();
    }

    public void upsertInstrument(long id, String isin, String symbol, String exchange, String name, String type,
                                 String assetClass, String proxy, String amfiCode, boolean synthetic) {
        jdbc.update("""
                INSERT INTO instrument_ref (instrument_id, isin, symbol, exchange, name, instrument_type, asset_class,
                                            asset_class_proxy, amfi_scheme_code, is_synthetic)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (instrument_id) DO UPDATE SET isin = EXCLUDED.isin, symbol = EXCLUDED.symbol,
                    exchange = EXCLUDED.exchange, name = EXCLUDED.name, instrument_type = EXCLUDED.instrument_type,
                    asset_class = EXCLUDED.asset_class, asset_class_proxy = EXCLUDED.asset_class_proxy,
                    amfi_scheme_code = EXCLUDED.amfi_scheme_code, is_synthetic = EXCLUDED.is_synthetic
                """, id, isin, symbol, exchange, name, type, assetClass, proxy, amfiCode, synthetic);
    }

    public void upsertPrice(PriceRecord r, InstrumentRef instrument, long ingestionId) {
        String conflict = r.source() == PriceSourceName.SYNTHETIC
                ? "(instrument_id, as_of_timestamp, source) WHERE source = 'SYNTHETIC'"
                : "(instrument_id, data_date, source) WHERE source <> 'SYNTHETIC'";
        jdbc.update("""
                INSERT INTO price (instrument_id, isin, price, currency, data_date, as_of_timestamp, source,
                                   fair_value_level, ingestion_id)
                VALUES (?, ?, ?, 'INR', ?, ?, ?, ?, ?)
                ON CONFLICT %s DO UPDATE SET price = EXCLUDED.price, data_date = EXCLUDED.data_date,
                    as_of_timestamp = EXCLUDED.as_of_timestamp, fair_value_level = EXCLUDED.fair_value_level,
                    ingestion_id = EXCLUDED.ingestion_id
                """.formatted(conflict), instrument.instrumentId(), instrument.isin(), r.price(),
                Date.valueOf(r.dataDate()), Timestamp.from(r.asOf()), r.source().name(), r.fairValueLevel(), ingestionId);
    }

    /** Instruments that already have a price for the date from one of the given sources. */
    public List<Long> pricedOn(LocalDate dataDate, Collection<PriceSourceName> sources) {
        if (sources.isEmpty()) {
            return List.of();
        }
        String in = sources.stream().map(s -> "'" + s.name() + "'").collect(Collectors.joining(","));
        return jdbc.queryForList("SELECT DISTINCT instrument_id FROM price WHERE data_date = ? AND source IN (" + in + ")",
                Long.class, Date.valueOf(dataDate));
    }

    /** Most recent price on or before {@code asOfDate}: latest data date, then latest timestamp, then source order. */
    public Optional<StoredPrice> latestPrice(long instrumentId, LocalDate asOfDate) {
        return jdbc.query("""
                SELECT p.* FROM price p WHERE p.instrument_id = ? AND p.data_date <= ?
                ORDER BY p.data_date DESC, p.as_of_timestamp DESC, %s LIMIT 1
                """.formatted(SOURCE_PRECEDENCE), MarketDataRepository::storedPrice, instrumentId, Date.valueOf(asOfDate))
                .stream().findFirst();
    }

    public List<StoredPrice> history(long instrumentId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT p.* FROM price p WHERE p.instrument_id = ? AND p.data_date BETWEEN ? AND ?
                ORDER BY p.data_date, p.as_of_timestamp, %s
                """.formatted(SOURCE_PRECEDENCE), MarketDataRepository::storedPrice, instrumentId, Date.valueOf(from),
                Date.valueOf(to));
    }

    public boolean alreadyLoaded(PriceSourceName source, String checksum) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM price_ingestion_log
                WHERE source = ? AND file_checksum = ? AND status IN ('SUCCESS', 'PARTIAL')
                """, Integer.class, source.name(), checksum);
        return count != null && count > 0;
    }

    public long insertLog(PriceBatch batch, String status, Instant startedAt) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO price_ingestion_log (source, data_date, file_name, file_checksum, status, started_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setString(1, batch.logSource().name());
            ps.setDate(2, batch.dataDate() == null ? null : Date.valueOf(batch.dataDate()));
            ps.setString(3, batch.fileName());
            ps.setString(4, batch.checksum());
            ps.setString(5, status);
            ps.setTimestamp(6, Timestamp.from(startedAt));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public void completeLog(long id, String status, int read, int loaded, int rejected, String error, Instant at) {
        jdbc.update("""
                UPDATE price_ingestion_log SET status = ?, rows_read = ?, rows_loaded = ?, rows_rejected = ?,
                    error_message = ?, completed_at = ? WHERE id = ?
                """, status, read, loaded, rejected, error, Timestamp.from(at), id);
    }

    public void insertErrors(long ingestionId, List<RejectedRow> rows) {
        for (RejectedRow r : rows) {
            jdbc.update("INSERT INTO price_ingestion_error (ingestion_id, row_number, isin, reason, raw_row) "
                    + "VALUES (?, ?, ?, ?, ?)", ingestionId, r.rowNumber(), r.isin(), truncate(r.reason(), 300),
                    truncate(r.rawRow(), 1000));
        }
    }

    public List<Map<String, Object>> ingestionLog(PriceSourceName source, int limit) {
        return source == null
                ? jdbc.queryForList("SELECT * FROM price_ingestion_log ORDER BY id DESC LIMIT ?", limit)
                : jdbc.queryForList("SELECT * FROM price_ingestion_log WHERE source = ? ORDER BY id DESC LIMIT ?",
                source.name(), limit);
    }

    public List<Map<String, Object>> ingestionErrors(long ingestionId) {
        return jdbc.queryForList("SELECT row_number, isin, reason, raw_row FROM price_ingestion_error "
                + "WHERE ingestion_id = ? ORDER BY row_number", ingestionId);
    }

    public List<Map<String, Object>> holidays(int year) {
        return jdbc.queryForList("SELECT holiday_date, exchange, segment, description FROM holiday_calendar "
                + "WHERE EXTRACT(YEAR FROM holiday_date) = ? ORDER BY holiday_date", year);
    }

    private static StoredPrice storedPrice(ResultSet rs, int i) throws SQLException {
        return new StoredPrice(rs.getLong("instrument_id"), rs.getString("isin"), rs.getBigDecimal("price"),
                rs.getString("currency"), rs.getObject("data_date", LocalDate.class),
                rs.getTimestamp("as_of_timestamp").toInstant(), PriceSourceName.valueOf(rs.getString("source")),
                rs.getString("fair_value_level"));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public record StoredPrice(long instrumentId, String isin, BigDecimal price, String currency, LocalDate dataDate,
                              Instant asOf, PriceSourceName source, String fairValueLevel) {
    }
}
