package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChineseSemanticMorphologyNormalizerTest {

    private final ChineseSemanticMorphologyNormalizer normalizer =
            new ChineseSemanticMorphologyNormalizer();

    @Test
    void segmentsContinuousHanTextDeterministically() {
        assertThat(normalizer.normalizeTokens("资本充足率"))
                .containsExactly(
                        "资",
                        "本",
                        "充",
                        "足",
                        "率"
                );
    }

    @Test
    void preservesLatinTechnicalRunsInsideChineseText() {
        assertThat(normalizer.normalizeTokens("DNA复制过程"))
                .containsExactly(
                        "dna",
                        "复",
                        "制",
                        "过",
                        "程"
                );
    }

    @Test
    void chineseDoesNotApplyLemmaOrStemMutation() {
        assertThat(normalizer.lemmaTokens("自然语言处理"))
                .isEqualTo(
                        normalizer.normalizeTokens("自然语言处理")
                );
        assertThat(normalizer.stemTokens("自然语言处理"))
                .isEqualTo(
                        normalizer.normalizeTokens("自然语言处理")
                );
    }
}
