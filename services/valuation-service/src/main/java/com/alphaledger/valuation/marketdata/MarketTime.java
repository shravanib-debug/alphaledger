package com.alphaledger.valuation.marketdata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HexFormat;

/** Small helpers shared by price sources. */
public final class MarketTime {

    public static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    /** NSE cash market close; end-of-day prices are timestamped at this time on their data date. */
    public static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private MarketTime() {
    }

    public static Instant marketClose(LocalDate date) {
        return date.atTime(MARKET_CLOSE).atZone(MARKET_ZONE).toInstant();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
