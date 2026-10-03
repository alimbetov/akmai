package kz.alimbetov.akmai.knowledge.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeLanguageTest {

    @Test
    void aliasesAndCaseCanonicalizeToStableLanguageCodes() {
        assertThat(KnowledgeLanguage.parse("KAZAKH").code())
                .isEqualTo("kk");
        assertThat(KnowledgeLanguage.parse("RUSSIAN").code())
                .isEqualTo("ru");
        assertThat(KnowledgeLanguage.parse("Eng").code())
                .isEqualTo("en");
        assertThat(KnowledgeLanguage.parse("CHI").code())
                .isEqualTo("zh");
        assertThat(KnowledgeLanguage.parse("Deutsch").code())
                .isEqualTo("de");
        assertThat(KnowledgeLanguage.parse("Français").code())
                .isEqualTo("fr");
        assertThat(KnowledgeLanguage.parse("ESPAÑOL").code())
                .isEqualTo("es");
        assertThat(KnowledgeLanguage.parse("pt-BR").code())
                .isEqualTo("pt");
        assertThat(KnowledgeLanguage.parse("Italiano").code())
                .isEqualTo("it");
        assertThat(KnowledgeLanguage.parse("Türkçe").code())
                .isEqualTo("tr");
        assertThat(KnowledgeLanguage.parse("Ελληνικά").code())
                .isEqualTo("el");
        assertThat(KnowledgeLanguage.parse("und").code())
                .isEqualTo("unknown");
    }

    @Test
    void retrievalCatalogCannotDriftFromDomainLanguageContract() {
        assertThat(RetrievalLanguageCatalog.codes())
                .containsExactlyElementsOf(List.of(
                        "kk",
                        "ru",
                        "en",
                        "zh",
                        "de",
                        "fr",
                        "es",
                        "pt",
                        "it",
                        "tr",
                        "el",
                        "unknown"
                ));
    }
}
