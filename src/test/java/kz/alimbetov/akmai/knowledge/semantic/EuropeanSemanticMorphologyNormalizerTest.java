package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EuropeanSemanticMorphologyNormalizerTest {

    @Test
    void germanAndFrenchProfilesRemainLanguageSpecific() {
        var german = new GermanSemanticMorphologyNormalizer();
        var french = new FrenchSemanticMorphologyNormalizer();
        var spanish = new SpanishSemanticMorphologyNormalizer();
        var portuguese = new PortugueseSemanticMorphologyNormalizer();
        var italian = new ItalianSemanticMorphologyNormalizer();

        assertThat(german.language()).isEqualTo("de");
        assertThat(french.language()).isEqualTo("fr");
        assertThat(spanish.language()).isEqualTo("es");
        assertThat(portuguese.language()).isEqualTo("pt");
        assertThat(italian.language()).isEqualTo("it");

        assertThat(german.lemmaTokens("risikogewichteten Aktiva"))
                .containsExactly("risikogewichtet", "aktiva");
        assertThat(french.lemmaTokens("transactions suspectes"))
                .containsExactly("transaction", "suspect");
        assertThat(spanish.lemmaTokens("activos ponderados"))
                .containsExactly("activ", "ponderad");
        assertThat(portuguese.lemmaTokens("ativos ponderados"))
                .containsExactly("ativ", "ponderad");
        assertThat(italian.lemmaTokens("attivi ponderati"))
                .containsExactly("attiv", "ponderat");
    }
}
