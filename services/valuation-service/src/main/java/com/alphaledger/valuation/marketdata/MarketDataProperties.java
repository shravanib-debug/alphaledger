package com.alphaledger.valuation.marketdata;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Market data settings (architecture.md §5.3).
 *
 * @param mode                     OFFLINE (local demo, tests, CI) or LIVE
 * @param networkEnabled           hard guard: HTTP fetches are refused when false
 * @param maxStalenessBusinessDays a stale price older than this also raises a data-quality warning
 * @param fallbackOrder            sources to try per instrument type, in order
 * @param seedEnabled              load the committed real seed prices and synthetic marks on startup
 * @param intradayTicksEnabled     OFFLINE only: add synthetic intraday ticks for demo movement
 */
@ConfigurationProperties("alphaledger.marketdata")
public record MarketDataProperties(Mode mode, Boolean networkEnabled, Integer maxStalenessBusinessDays,
                                   Map<String, List<PriceSourceName>> fallbackOrder, Boolean seedEnabled,
                                   Boolean intradayTicksEnabled) {

    public enum Mode { OFFLINE, LIVE }

    public MarketDataProperties {
        mode = mode == null ? Mode.OFFLINE : mode;
        networkEnabled = networkEnabled != null && networkEnabled;
        maxStalenessBusinessDays = maxStalenessBusinessDays == null ? 5 : maxStalenessBusinessDays;
        fallbackOrder = fallbackOrder == null ? Map.of() : Map.copyOf(fallbackOrder);
        seedEnabled = seedEnabled == null || seedEnabled;
        intradayTicksEnabled = intradayTicksEnabled != null && intradayTicksEnabled;
    }
}
