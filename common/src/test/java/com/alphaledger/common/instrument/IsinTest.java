package com.alphaledger.common.instrument;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IsinTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "US0378331005", // Apple, the textbook example
            "INE002A01018", // Reliance Industries
            "INE041025011", // Embassy Office Parks REIT
            "IN0020260041", // GOI 7.06% 2041
            "INF179K01XD8"  // HDFC Corporate Bond Fund, Direct, Growth
    })
    void acceptsRealIsins(String isin) {
        assertThat(Isin.isValid(isin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"INE002A01019", "US0378331006", "INE00A01018", "ine002a01018", "INE002A0101X", ""})
    void rejectsWrongCheckDigitOrFormat(String isin) {
        assertThat(Isin.isValid(isin)).isFalse();
    }

    @Test
    void recognisesSyntheticPrefixAndNormalizes() {
        assertThat(Isin.isSynthetic("ZZSYN0000001")).isTrue();
        assertThat(Isin.isSynthetic("INE002A01018")).isFalse();
        assertThat(Isin.normalize(" ine002a01018 ")).isEqualTo("INE002A01018");
        assertThat(Isin.isValid(null)).isFalse();
    }
}
