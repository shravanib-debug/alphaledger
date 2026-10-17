package com.alphaledger.valuation.marketdata;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Market data settings (architecture.md §5.3).
 *
 * @param mode                     OFFLINE (local demo, tests, CI) or LIVE
 * @param networkEnabled           hard guard: HTTP fetches are refused when false (and always in OFFLINE mode)
 * @param maxStalenessBusinessDays a stale price older than this also raises a data-quality warning
 * @param fallbackOrder            sources to try per instrument type, in order
 * @param seedEnabled              load the committed real seed prices and synthetic marks on startup
 * @param intradayTicksEnabled     OFFLINE only: add synthetic intraday ticks for demo movement
 * @param sampleEnabled            OFFLINE only: on startup, run the bhavcopy and NAVAll parsers over every committed
 *                                 sample file ({@code reference-data/sample/nse|amfi})
 * @param cacheDir                 LIVE only: where downloaded raw files are kept ({source}/{yyyy}/{file}, git-ignored)
 * @param nseUrl                   base URL of the NSE CM bhavcopy archive
 * @param amfiUrl                  URL of AMFI's NAVAll.txt (latest NAVs only)
 * @param mfapiUrl                 base URL of mfapi.in (fallback for missing NAVs)
 * @param nseDownload              LIVE only: download missing bhavcopy files from NSE. Off by default: NSE's terms of
 *                                 use prohibit systematic or automated data collection, so files are normally saved by
 *                                 hand into {@code cache-dir/NSE_BHAVCOPY/{yyyy}/} (switch on only with NSE's consent,
 *                                 e.g. an NSE Data licence)
 */
@ConfigurationProperties("alphaledger.marketdata")
public record MarketDataProperties(Mode mode, Boolean networkEnabled, Integer maxStalenessBusinessDays,
                                   Map<String, List<PriceSourceName>> fallbackOrder, Boolean seedEnabled,
                                   Boolean intradayTicksEnabled, Boolean sampleEnabled, String cacheDir,
                                   String nseUrl, String amfiUrl, String mfapiUrl,
                                   Boolean nseDownload) {

    public enum Mode { OFFLINE, LIVE }

    public MarketDataProperties {
        mode = mode == null ? Mode.OFFLINE : mode;
        networkEnabled = networkEnabled != null && networkEnabled;
        maxStalenessBusinessDays = maxStalenessBusinessDays == null ? 5 : maxStalenessBusinessDays;
        fallbackOrder = fallbackOrder == null ? Map.of() : Map.copyOf(fallbackOrder);
        seedEnabled = seedEnabled == null || seedEnabled;
        intradayTicksEnabled = intradayTicksEnabled != null && intradayTicksEnabled;
        sampleEnabled = sampleEnabled == null || sampleEnabled;
        cacheDir = cacheDir == null ? "data/market-cache" : cacheDir;
        nseUrl = nseUrl == null ? "https://nsearchives.nseindia.com/content/cm" : nseUrl;
        amfiUrl = amfiUrl == null ? "https://www.amfiindia.com/spages/NAVAll.txt" : amfiUrl;
        mfapiUrl = mfapiUrl == null ? "https://api.mfapi.in" : mfapiUrl;
        nseDownload = nseDownload != null && nseDownload;
    }

    /** Settings for tests and simple callers: everything else at its default. */
    public static MarketDataProperties of(Mode mode, boolean networkEnabled, Map<String, List<PriceSourceName>> order) {
        return new MarketDataProperties(mode, networkEnabled, 5, order, true, false, true, null, null, null, null, false);
    }

    /** HTTP is allowed only in LIVE mode with the network guard switched on. */
    public boolean networkAllowed() {
        return mode == Mode.LIVE && networkEnabled;
    }
}
