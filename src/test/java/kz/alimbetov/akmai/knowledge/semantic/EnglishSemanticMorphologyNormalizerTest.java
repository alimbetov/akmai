package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EnglishSemanticMorphologyNormalizerTest {

    private final EnglishSemanticMorphologyNormalizer normalizer =
            new EnglishSemanticMorphologyNormalizer();

    @Test
    void normalizesCommonInflectionalEndingsConservatively() {
        assertThat(normalizer.lemmaTokens(
                "Risk weighted assets and machine learning models"
        )).containsExactly(
                "risk",
                "weight",
                "asset",
                "and",
                "machine",
                "learn",
                "model"
        );
    }

    @Test
    void stemsDerivationalFormsAfterLemmatization() {
        assertThat(normalizer.stemTokens(
                "payment fraud detection"
        )).containsExactly(
                "pay",
                "fraud",
                "detect"
        );
    }

    @Test
    void preservesShortAndProtectedPluralLikeTokens() {
        assertThat(normalizer.lemmaTokens(
                "data gas series"
        )).containsExactly(
                "data",
                "gas",
                "series"
        );
    }
}
