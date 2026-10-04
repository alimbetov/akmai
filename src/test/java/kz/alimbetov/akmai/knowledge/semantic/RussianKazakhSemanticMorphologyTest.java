package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RussianKazakhSemanticMorphologyTest {

    private final RussianSemanticMorphologyNormalizer russian =
            new RussianSemanticMorphologyNormalizer();
    private final KazakhSemanticMorphologyNormalizer kazakh =
            new KazakhSemanticMorphologyNormalizer();

    @Test
    void russianNormalizesInflectedPhraseTokens() {
        assertThat(russian.lemmaTokens(
                "коэффициента достаточности капитала"
        )).containsExactly(
                "коэффициент",
                "достаточност",
                "капитал"
        );

        assertThat(russian.lemmaPhrase(
                "коэффициент достаточности капитала"
        )).isEqualTo(
                "коэффициент достаточност капитал"
        );
    }

    @Test
    void kazakhNormalizesCaseAndPluralSuffixesConservatively() {
        assertThat(kazakh.lemmaTokens(
                "құбырдың тұтастығын басқару"
        )).containsExactly(
                "құбыр",
                "тұтастығы",
                "басқару"
        );

        assertThat(kazakh.lemmaTokens(
                "құбыр тұтастығын басқару"
        )).containsExactly(
                "құбыр",
                "тұтастығы",
                "басқару"
        );
    }

    @Test
    void languageSpecificNormalizersRemainDifferent() {
        assertThat(russian.language()).isEqualTo("ru");
        assertThat(kazakh.language()).isEqualTo("kk");
        assertThat(russian.lemmaTokens("капитала"))
                .isNotEqualTo(kazakh.lemmaTokens("капитала"));
    }
}
