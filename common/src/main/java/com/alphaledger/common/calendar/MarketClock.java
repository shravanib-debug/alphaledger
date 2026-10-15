package com.alphaledger.common.calendar;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * "Today" for trading purposes. Services run in UTC, but the trading day follows the market's time zone
 * (Asia/Kolkata for NSE): at 02:00 IST it is already the next trading day although it is still yesterday in UTC.
 */
public class MarketClock {

    private final Clock clock;
    private final ZoneId marketZone;

    public MarketClock(Clock clock, ZoneId marketZone) {
        this.clock = clock;
        this.marketZone = marketZone;
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), marketZone);
    }

    public Instant now() {
        return clock.instant();
    }

    public ZoneId zone() {
        return marketZone;
    }
}
