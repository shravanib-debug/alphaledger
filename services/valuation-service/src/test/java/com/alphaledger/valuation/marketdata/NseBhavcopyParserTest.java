package com.alphaledger.valuation.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.alphaledger.valuation.marketdata.PriceBatch.PriceRecord;

/** The UDiFF bhavcopy parser against real (filtered) NSE files from the committed offline sample. */
class NseBhavcopyParserTest {

    private static final String HEADER = "TradDt,BizDt,Sgmt,Src,FinInstrmTp,FinInstrmId,ISIN,TckrSymb,SctySrs,XpryDt,"
            + "FininstrmActlXpryDt,StrkPric,OptnTp,FinInstrmNm,OpnPric,HghPric,LwPric,ClsPric,LastPric,PrvsClsgPric,"
            + "UndrlygPric,SttlmPric,OpnIntrst,ChngInOpnIntrst,TtlTradgVol,TtlTrfVal,TtlNbOfTxsExctd,SsnId,NewBrdLotQty,"
            + "Rmks,Rsvd1,Rsvd2,Rsvd3,Rsvd4";
    private static final LocalDate OCT_16 = LocalDate.of(2026, 10, 16);

    @Test
    void readsTheClosingPriceOfEveryInstrumentInARealFile() throws IOException {
        PriceBatch batch = parse(OCT_16);

        assertThat(batch.logSource()).isEqualTo(PriceSourceName.NSE_BHAVCOPY);
        assertThat(batch.rejected()).isEmpty();
        assertThat(batch.records()).hasSize(18);
        assertThat(prices(batch)).containsEntry("INE002A01018", new BigDecimal("1211.20"))   // Reliance, EQ
                .containsEntry("INE041025011", new BigDecimal("435.40"))                    // Embassy REIT
                .doesNotContainKey("INE134E07570");                                        // PFC bond: no trade that day
        assertThat(batch.records()).allSatisfy(r -> {
            assertThat(r.dataDate()).isEqualTo(OCT_16);
            assertThat(r.source()).isEqualTo(PriceSourceName.NSE_BHAVCOPY);
        });
        assertThat(batch.checksum()).hasSize(64);
    }

    /** 8-Oct-2026: Reliance and ICICI Bank also traded in the block-deal window (series BL) at other prices. */
    @Test
    void ignoresBlockDealRowsAndKeepsTheNormalMarketClose() throws IOException {
        PriceBatch batch = parse(LocalDate.of(2026, 10, 8));

        assertThat(batch.records()).extracting(PriceRecord::isin).doesNotHaveDuplicates();
        assertThat(prices(batch)).containsEntry("INE002A01018", new BigDecimal("1178.00"))
                .containsEntry("INE090A01021", new BigDecimal("1349.00"));
    }

    @Test
    void anEqRowWinsOverAnotherSeriesForTheSameIsin() {
        String file = HEADER + "\n" + row("INE002A01018", "BE", "1170.00") + row("INE002A01018", "EQ", "1178.00");

        assertThat(prices(parse(file))).containsEntry("INE002A01018", new BigDecimal("1178.00"));
    }

    @Test
    void aChangedHeaderRefusesTheWholeFile() {
        String file = HEADER.replace("ClsPric", "ClosePrice") + "\n" + row("INE002A01018", "EQ", "1178.00");

        assertThatThrownBy(() -> parse(file)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing column(s) [ClsPric]");
    }

    @Test
    void zeroBlankAndNonNumericPricesAndWrongDatesAreRejectedWithAReason() {
        String file = HEADER + "\n"
                + row("INE002A01018", "EQ", "0") + row("INE040A01034", "EQ", "")
                + row("INE090A01021", "EQ", "abc") + row("INE009A01021", "EQ", "1500.00").replace("2026-10-16", "2026-10-15")
                + row("INE467B01029", "EQ", "3000.00");

        PriceBatch batch = parse(file);

        assertThat(batch.records()).extracting(PriceRecord::isin).containsExactly("INE467B01029");
        assertThat(batch.rejected()).extracting(PriceBatch.RejectedRow::reason).containsExactly(
                "ClsPric must be greater than zero", "blank ClsPric", "Character a is neither a decimal digit number, "
                        + "decimal point, nor \"e\" notation exponential mark.",
                "TradDt 2026-10-15 does not match file date 2026-10-16");
    }

    @Test
    void unknownIsinsAreLeftForThePipelineToSkip() {
        PriceBatch batch = parse(HEADER + "\n" + row("INE000000000", "EQ", "10.00"));

        assertThat(batch.records()).extracting(PriceRecord::isin).containsExactly("INE000000000");
        assertThat(batch.rejectUnknownIsins()).isFalse();   // market-wide file: unknown ISINs are skipped silently
    }

    @Test
    void thereIsNoFileForAHoliday() {
        MarketDataProperties offline = MarketDataProperties.of(MarketDataProperties.Mode.OFFLINE, false, Map.of());
        NseBhavcopyPriceSource source = new NseBhavcopyPriceSource(
                new MarketDataFiles(offline, new MarketDataHttp(offline)));

        // 20-Oct-2026 is Dussehra: NSE publishes no bhavcopy.
        assertThatThrownBy(() -> source.fetch(LocalDate.of(2026, 10, 20), List.of()))
                .isInstanceOf(PriceFileUnavailableException.class).hasMessageContaining("2026-10-20");
    }

    private static PriceBatch parse(LocalDate date) throws IOException {
        String name = MarketDataFiles.bhavcopyName(date);
        try (InputStream in = new ClassPathResource(MarketDataFiles.NSE_SAMPLE + name).getInputStream()) {
            return NseBhavcopyParser.parse(name, in.readAllBytes(), date);
        }
    }

    private static PriceBatch parse(String content) {
        return NseBhavcopyParser.parse("test.csv", content.getBytes(StandardCharsets.UTF_8), OCT_16);
    }

    private static String row(String isin, String series, String close) {
        return "2026-10-16,2026-10-16,CM,NSE,STK,1," + isin + ",SYM," + series + ",,,,,NAME,1,1,1," + close
                + ",1,1,,1,,,1,1,1,F1,1,,,,,\n";
    }

    private static Map<String, BigDecimal> prices(PriceBatch batch) {
        return batch.records().stream().collect(java.util.stream.Collectors.toMap(PriceRecord::isin, PriceRecord::price));
    }
}
