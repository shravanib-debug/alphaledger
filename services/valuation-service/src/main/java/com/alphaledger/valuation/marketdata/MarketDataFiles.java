package com.alphaledger.valuation.marketdata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Raw source files (architecture.md §5.3, step 1 "Extract"). In OFFLINE mode they come from the committed sample
 * ({@code reference-data/sample/nse|amfi}, on the classpath), so the real parsers still run without a network. In LIVE
 * mode they are read from {@code cache-dir/{source}/{yyyy}/{file}}. AMFI files are downloaded into the cache when
 * missing; NSE files are only downloaded when {@code nse-download} is on (NSE's terms prohibit automated collection),
 * otherwise they are saved there by hand.
 */
@Component
public class MarketDataFiles {

    static final String NSE_SAMPLE = "reference-data/sample/nse/";
    static final String AMFI_SAMPLE = "reference-data/sample/amfi/";
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern NSE_NAME = Pattern.compile("BhavCopy_NSE_CM_0_0_0_(\\d{8})_F_0000\\.csv");
    private static final Pattern AMFI_NAME = Pattern.compile("NAVAll_(\\d{4}-\\d{2}-\\d{2})\\.txt");
    private static final Logger log = LoggerFactory.getLogger(MarketDataFiles.class);

    private final MarketDataProperties properties;
    private final MarketDataHttp http;

    public MarketDataFiles(MarketDataProperties properties, MarketDataHttp http) {
        this.properties = properties;
        this.http = http;
    }

    /** A source file: its name and content (already unzipped). */
    public record RawFile(String name, byte[] content) {
    }

    public static String bhavcopyName(LocalDate date) {
        return "BhavCopy_NSE_CM_0_0_0_" + date.format(COMPACT) + "_F_0000.csv";
    }

    /** NSE CM bhavcopy (UDiFF) for a trading date; empty when there is none (holiday, weekend, not yet published). */
    public Optional<RawFile> nseBhavcopy(LocalDate date) {
        String name = bhavcopyName(date);
        if (properties.mode() == MarketDataProperties.Mode.OFFLINE) {
            return classpath(NSE_SAMPLE + name).map(bytes -> new RawFile(name, bytes));
        }
        Path cached = cachePath(PriceSourceName.NSE_BHAVCOPY, date, name + ".zip");
        if (!properties.nseDownload() && !Files.isRegularFile(cached)) {
            log.info("No bhavcopy for {} in {}; download it from nseindia.com (All Reports > Capital Market) and save it"
                    + " there (automatic NSE download is off)", date, cached.getParent());
            return Optional.empty();
        }
        try {
            byte[] zip = cachedOrDownload(cached, properties.nseUrl() + "/" + name + ".zip");
            return Optional.of(new RawFile(name, unzip(zip, name)));
        } catch (MarketDataHttp.NotAvailableException ex) {
            log.info("No bhavcopy for {}: {}", date, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * AMFI NAVAll.txt. OFFLINE: the committed sample for {@code date}. LIVE: AMFI publishes only the latest NAVs, so the
     * current file is downloaded (once per day) whatever the requested date; each row carries its own NAV date.
     */
    public Optional<RawFile> amfiNav(LocalDate date) {
        String name = "NAVAll_" + date + ".txt";
        if (properties.mode() == MarketDataProperties.Mode.OFFLINE) {
            return classpath(AMFI_SAMPLE + name).map(bytes -> new RawFile(name, bytes));
        }
        Path cached = cachePath(PriceSourceName.AMFI_NAV, date, name);
        try {
            return Optional.of(new RawFile(name, cachedOrDownload(cached, properties.amfiUrl())));
        } catch (MarketDataHttp.NotAvailableException ex) {
            log.info("NAVAll.txt not available: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** Dates of the committed NSE sample files, oldest first. */
    public List<LocalDate> nseSampleDates() {
        return sampleDates(NSE_SAMPLE + "*.csv", NSE_NAME, s -> LocalDate.parse(s, COMPACT));
    }

    /** Dates of the committed AMFI sample files, oldest first. */
    public List<LocalDate> amfiSampleDates() {
        return sampleDates(AMFI_SAMPLE + "*.txt", AMFI_NAME, LocalDate::parse);
    }

    private static List<LocalDate> sampleDates(String pattern, Pattern name,
                                               java.util.function.Function<String, LocalDate> parse) {
        TreeSet<LocalDate> dates = new TreeSet<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:" + pattern)) {
                Matcher m = name.matcher(r.getFilename() == null ? "" : r.getFilename());
                if (m.matches()) {
                    dates.add(parse.apply(m.group(1)));
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new ArrayList<>(dates);
    }

    private Path cachePath(PriceSourceName source, LocalDate date, String file) {
        return Path.of(properties.cacheDir(), source.name(), String.valueOf(date.getYear()), file);
    }

    private byte[] cachedOrDownload(Path cached, String url) {
        try {
            if (Files.isRegularFile(cached) && Files.size(cached) > 0) {
                return Files.readAllBytes(cached);
            }
            byte[] bytes = http.get(url);
            Files.createDirectories(cached.getParent());
            Path part = cached.resolveSibling(cached.getFileName() + ".part");
            Files.write(part, bytes);
            Files.move(part, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("Downloaded {} ({} bytes) to {}", url, bytes.length, cached);
            return bytes;
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot use cache file " + cached, ex);
        }
    }

    static byte[] unzip(byte[] zip, String entryName) {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().endsWith(entryName)) {
                    return in.readAllBytes();
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Unreadable zip for " + entryName, ex);
        }
        throw new IllegalStateException("Zip does not contain " + entryName);
    }

    private static Optional<byte[]> classpath(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            return Optional.of(in.readAllBytes());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
