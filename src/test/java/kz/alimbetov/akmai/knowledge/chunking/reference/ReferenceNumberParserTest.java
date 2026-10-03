package kz.alimbetov.akmai.knowledge.chunking.reference;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReferenceNumberParserTest {

    @Test
    void validatesRomanNumeralsInsteadOfAcceptingArbitrarySequences() {
        assertThat(ReferenceNumberParser.romanToInt("XII"))
                .hasValue(12);
        assertThat(ReferenceNumberParser.romanToInt("IIII"))
                .isEmpty();
    }

    @Test
    void parsesChineseLargeUnitsCorrectly() {
        assertThat(ReferenceNumberParser.canonicalChinese("十万"))
                .isEqualTo("100000");
        assertThat(ReferenceNumberParser.canonicalChinese("十二万三千四百五十六"))
                .isEqualTo("123456");
    }

    @Test
    void boundsRangeExpansion() {
        assertThat(ReferenceNumberParser.expandIntegerRange("5", "7", 100))
                .containsExactly("5", "6", "7");
        assertThat(ReferenceNumberParser.expandIntegerRange("5", "1000", 100))
                .containsExactly("5");
    }
}
