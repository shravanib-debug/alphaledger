package com.alphaledger.valuation.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

/** Where raw files come from: the committed sample (OFFLINE) or the local cache / drop folder (LIVE). */
class MarketDataFilesTest {

    private static final LocalDate OCT_16 = LocalDate.of(2026, 10, 16);

    @TempDir
    Path cache;

    @Test
    void liveModeReadsAHandSavedBhavcopyAndNeverDownloadsFromNse() throws IOException {
        String name = MarketDataFiles.bhavcopyName(OCT_16);
        Path dir = Files.createDirectories(cache.resolve("NSE_BHAVCOPY/2026"));
        try (OutputStream out = Files.newOutputStream(dir.resolve(name + ".zip"));
             ZipOutputStream zip = new ZipOutputStream(out);
             InputStream sample = new ClassPathResource(MarketDataFiles.NSE_SAMPLE + name).getInputStream()) {
            zip.putNextEntry(new ZipEntry(name));
            sample.transferTo(zip);
            zip.closeEntry();
        }
        MarketDataHttp http = mock(MarketDataHttp.class);
        MarketDataFiles files = new MarketDataFiles(live(false), http);

        MarketDataFiles.RawFile file = files.nseBhavcopy(OCT_16).orElseThrow();
        PriceBatch batch = NseBhavcopyParser.parse(file.name(), file.content(), OCT_16);

        assertThat(batch.records()).hasSize(18);
        assertThat(files.nseBhavcopy(LocalDate.of(2026, 10, 15))).isEmpty();   // not saved: a gap, no download
        verifyNoInteractions(http);
    }

    @Test
    void offlineModeReadsOnlyTheCommittedSample() {
        MarketDataHttp http = mock(MarketDataHttp.class);
        MarketDataFiles files = new MarketDataFiles(
                MarketDataProperties.of(MarketDataProperties.Mode.OFFLINE, false, Map.of()), http);

        assertThat(files.nseSampleDates()).hasSize(7).startsWith(LocalDate.of(2026, 10, 8)).endsWith(OCT_16);
        assertThat(files.amfiSampleDates()).containsExactly(OCT_16);
        assertThat(files.amfiNav(OCT_16)).isPresent();
        assertThat(files.nseBhavcopy(LocalDate.of(2026, 10, 1))).isEmpty();
        verifyNoInteractions(http);
    }

    private MarketDataProperties live(boolean nseDownload) {
        return new MarketDataProperties(MarketDataProperties.Mode.LIVE, true, 5, Map.of(), true, false, false,
                cache.toString(), null, null, null, nseDownload);
    }
}
