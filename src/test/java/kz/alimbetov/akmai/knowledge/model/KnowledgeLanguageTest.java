package kz.alimbetov.akmai.knowledge.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnowledgeLanguageTest {

    @Test
    void aliasesAndCaseCanonicalizeToStableLanguageCodes() {
        assertThat(KnowledgeLanguage.parse("KAZAKH").code()).isEqualTo("kk");
        assertThat(KnowledgeLanguage.parse("RUSSIAN").code()).isEqualTo("ru");
        assertThat(KnowledgeLanguage.parse("Eng").code()).isEqualTo("en");
        assertThat(KnowledgeLanguage.parse("CHI").code()).isEqualTo("zh");
    }
}
