package com.alphaledger.portfolio.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.alphaledger.common.instrument.Isin;
import com.alphaledger.portfolio.domain.AssetClass;

/** Guards the committed instrument master: every real ISIN has a valid check digit and IDs are stable. */
class InstrumentMasterFileTest {

    @Test
    void committedMasterHas26RealAnd3SyntheticInstrumentsWithValidIsins() throws Exception {
        List<InstrumentMasterFile.Row> rows;
        try (InputStream in = new ClassPathResource(ReferenceDataLoader.RESOURCE).getInputStream()) {
            rows = InstrumentMasterFile.read(in);
        }

        assertThat(rows).hasSize(29);
        assertThat(rows).filteredOn(InstrumentMasterFile.Row::synthetic).hasSize(3)
                .allSatisfy(row -> assertThat(row.isin()).startsWith(Isin.SYNTHETIC_PREFIX));
        assertThat(rows).filteredOn(row -> !row.synthetic()).hasSize(26)
                .allSatisfy(row -> assertThat(Isin.isValid(row.isin())).as(row.isin()).isTrue());
        assertThat(rows).extracting(InstrumentMasterFile.Row::instrumentId)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 29).boxed().toList());

        Map<AssetClass, Long> byClass = rows.stream()
                .collect(Collectors.groupingBy(InstrumentMasterFile.Row::assetClass, Collectors.counting()));
        assertThat(byClass).containsEntry(AssetClass.REAL_ESTATE, 8L)
                .containsEntry(AssetClass.CREDIT, 9L)
                .containsEntry(AssetClass.PRIVATE_EQUITY, 12L);
    }

    @Test
    void keepsCommasInsideQuotedFields() {
        assertThat(InstrumentMasterFile.splitCsv("14,INF179K01XD8,,,\"HDFC Corporate Bond Fund, Direct, Growth\",x"))
                .containsExactly("14", "INF179K01XD8", "", "", "HDFC Corporate Bond Fund, Direct, Growth", "x");
        assertThat(InstrumentMasterFile.splitCsv("a,\"say \"\"hi\"\"\",b")).containsExactly("a", "say \"hi\"", "b");
    }

    @Test
    void rejectsAWrongCheckDigitOrDuplicateId() {
        String badIsin = InstrumentMasterFile.HEADER
                + "\n1,INE002A01019,RELIANCE,NSE,Reliance,EQUITY,PRIVATE_EQUITY,LISTED_EQUITY,,false\n";
        String duplicate = InstrumentMasterFile.HEADER
                + "\n1,INE002A01018,RELIANCE,NSE,Reliance,EQUITY,PRIVATE_EQUITY,LISTED_EQUITY,,false"
                + "\n1,INE040A01034,HDFCBANK,NSE,HDFC Bank,EQUITY,PRIVATE_EQUITY,LISTED_EQUITY,,false\n";

        assertThatThrownBy(() -> InstrumentMasterFile.read(stream(badIsin))).hasMessageContaining("check digit");
        assertThatThrownBy(() -> InstrumentMasterFile.read(stream(duplicate))).hasMessageContaining("Duplicate");
    }

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
