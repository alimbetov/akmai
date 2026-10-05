package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TurkishGreekSemanticMorphologyTest {

    @Test
    void turkishUsesLocaleAwareCaseAndBoundedAgglutinativeStripping() {
        var normalizer = new TurkishSemanticMorphologyNormalizer();

        assertThat(normalizer.normalizeTokens("İÇSEL IŞIK"))
                .containsExactly("içsel", "ışık");
        assertThat(normalizer.lemmaTokens("müşterilerinizden"))
                .containsExactly("müşter");
    }

    @Test
    void greekNormalizesTonosFinalSigmaAndInflection() {
        var normalizer = new GreekSemanticMorphologyNormalizer();

        assertThat(normalizer.normalizeTokens(
                "ΔΕΊΚΤΗΣ ΡΕΥΣΤΌΤΗΤΑΣ"
        )).containsExactly("δεικτησ", "ρευστοτητασ");

        assertThat(normalizer.lemmaTokens(
                "δείκτες κεφαλαιακής επάρκειας"
        )).containsExactly(
                "δεικτ",
                "κεφαλαιακ",
                "επαρκε"
        );
    }
}
