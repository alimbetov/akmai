package kz.alimbetov.akmai.rag.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class QueryLanguageDetectorTest {

    private final QueryLanguageDetector detector =
            new QueryLanguageDetector();

    @Test
    void detectsAllSupportedLanguages() {
        assertThat(detector.detect("Қандай доза қажет?")).isEqualTo("kk");
        assertThat(detector.detect("Какие противопоказания указаны?"))
                .isEqualTo("ru");
        assertThat(detector.detect("What monitoring is required?"))
                .isEqualTo("en");
        assertThat(detector.detect("剂量是多少？")).isEqualTo("zh");
        assertThat(detector.detect(
                "Welche Kontraindikationen sind angegeben?"
        )).isEqualTo("de");
        assertThat(detector.detect(
                "Quelle surveillance est requise?"
        )).isEqualTo("fr");
        assertThat(detector.detect(
                "¿Qué dosis se recomienda?"
        )).isEqualTo("es");
        assertThat(detector.detect(
                "Qual monitorização é necessária?"
        )).isEqualTo("pt");
        assertThat(detector.detect(
                "Quale dose è richiesta?"
        )).isEqualTo("it");
        assertThat(detector.detect(
                "Hangi doz gereklidir?"
        )).isEqualTo("tr");
        assertThat(detector.detect(
                "Ποια δόση απαιτείται;"
        )).isEqualTo("el");
    }

    @Test
    void ambiguousScriptsFailOpenToBoundedCandidates() {
        LanguageDecision cyrillic = detector.decision("Срок 30 дней");
        assertThat(cyrillic.primary()).isEqualTo("unknown");
        assertThat(cyrillic.candidates())
                .containsOnly("kk", "ru");

        LanguageDecision latin = detector.decision("Article 15");
        assertThat(latin.primary()).isEqualTo("unknown");
        assertThat(latin.candidates())
                .hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void emptyOrNonLexicalQueryIsUnknown() {
        assertThat(detector.decision("").primary())
                .isEqualTo("unknown");
        assertThat(detector.decision("12345").candidates())
                .isEqualTo(List.of());
    }
}
