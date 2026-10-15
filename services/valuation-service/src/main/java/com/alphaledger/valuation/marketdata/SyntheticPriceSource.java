package com.alphaledger.valuation.marketdata;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.alphaledger.valuation.marketdata.PriceBatch.PriceRecord;
import com.alphaledger.valuation.marketdata.PriceBatch.RejectedRow;

/**
 * Synthetic prices (prd.md FR-DATA-7). It prices only instruments with {@code is_synthetic = true}, from the committed
 * quarterly marks ({@value #MARKS}, fair-value Level 3, held flat between quarter-ends). In OFFLINE mode it can also
 * generate small seeded random-walk intraday ticks around a real instrument's latest price for demo movement; those
 * ticks are stored with source SYNTHETIC, so the source badge always shows them for what they are.
 */
@Component
public class SyntheticPriceSource implements PriceSource {

    static final String MARKS = "reference-data/synthetic/synthetic_marks.csv";
    static final String HEADER = "isin,valuation_date,price,fair_value_level,note";
    private static final double MAX_TICK_MOVE = 0.005;   // at most ±0.5% per tick

    @Override
    public PriceSourceName name() {
        return PriceSourceName.SYNTHETIC;
    }

    @Override
    public boolean supports(InstrumentRef instrument) {
        return instrument.synthetic();
    }

    /** Every mark dated on or before {@code dataDate} for the requested synthetic instruments. */
    @Override
    public PriceBatch fetch(LocalDate dataDate, Collection<InstrumentRef> instruments) {
        String content = readMarks();
        Set<String> wanted = instruments.stream().filter(this::supports).map(InstrumentRef::isin)
                .collect(Collectors.toSet());
        List<PriceRecord> records = new ArrayList<>();
        List<RejectedRow> rejected = new ArrayList<>();
        Set<LocalDate> included = new TreeSet<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            if (!HEADER.equals(reader.readLine())) {
                throw new IllegalStateException("Unexpected header in " + MARKS);
            }
            String line;
            int row = 1;
            while ((line = reader.readLine()) != null) {
                row++;
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split(",", 5);
                try {
                    LocalDate date = LocalDate.parse(f[1].trim());
                    if (!wanted.contains(f[0].trim()) || date.isAfter(dataDate)) {
                        continue;
                    }
                    records.add(new PriceRecord(row, f[0].trim(), date, MarketTime.marketClose(date),
                            new BigDecimal(f[2].trim()), PriceSourceName.SYNTHETIC, f[3].trim()));
                    included.add(date);
                } catch (RuntimeException ex) {
                    rejected.add(new RejectedRow(row, f[0], "unreadable mark: " + ex.getMessage(), line));
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        // A new quarter-end mark changes the checksum, so it is loaded; re-running the same day is skipped.
        String checksum = MarketTime.sha256(content + "|" + included);
        return new PriceBatch(PriceSourceName.SYNTHETIC, dataDate, "synthetic_marks.csv", checksum, records, rejected,
                false, false);
    }

    /**
     * OFFLINE demo ticks: one price per instrument, within ±0.5% of its latest price. Seeded by instrument and minute,
     * so the same minute always produces the same tick.
     */
    public PriceBatch intradayTicks(Map<InstrumentRef, BigDecimal> latestPrices, LocalDate today, Instant now) {
        List<PriceRecord> records = new ArrayList<>();
        long minute = now.getEpochSecond() / 60;
        int row = 0;
        for (Map.Entry<InstrumentRef, BigDecimal> entry : latestPrices.entrySet()) {
            Random random = new Random(entry.getKey().instrumentId() * 1_000_003L ^ minute);
            double move = Math.max(-MAX_TICK_MOVE, Math.min(MAX_TICK_MOVE, random.nextGaussian() * MAX_TICK_MOVE / 2));
            BigDecimal price = entry.getValue().multiply(BigDecimal.valueOf(1 + move))
                    .setScale(Math.max(2, entry.getValue().scale()), RoundingMode.HALF_UP);
            records.add(new PriceRecord(++row, entry.getKey().isin(), today, now, price, PriceSourceName.SYNTHETIC,
                    null));
        }
        return new PriceBatch(PriceSourceName.SYNTHETIC, today, "intraday-ticks", MarketTime.sha256("ticks|" + now),
                records, List.of(), false, true);
    }

    private static String readMarks() {
        try (InputStream in = new ClassPathResource(MARKS).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read " + MARKS, ex);
        }
    }
}
