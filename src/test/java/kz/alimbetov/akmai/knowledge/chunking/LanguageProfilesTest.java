package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;
import org.junit.jupiter.api.Test;

class LanguageProfilesTest {

    @Test
    void resolvesAllSupportedLanguagesAndPrimarySubtags() {
        for (KnowledgeLanguage language : KnowledgeLanguage.values()) {
            assertThat(LanguageProfiles.forLanguage(language).language())
                    .isEqualTo(language);
        }

        assertThat(LanguageProfiles.forCode("pt-BR").language())
                .isEqualTo(KnowledgeLanguage.PT);
        assertThat(LanguageProfiles.forCode("de-DE").language())
                .isEqualTo(KnowledgeLanguage.DE);
    }

    @Test
    void unsupportedLanguageUsesNeutralProfileInsteadOfEnglish() {
        assertThat(LanguageProfiles.forCode("ja").language())
                .isEqualTo(KnowledgeLanguage.UNKNOWN);
        assertThat(LanguageProfiles.forCode("ja").abbreviations())
                .isEmpty();
    }

    @Test
    void greekProfileKeepsSemicolonAsSentenceTerminal() {
        assertThat(LanguageProfiles.forCode("el").terminalChars())
                .contains(';', ';');
    }

    @Test
    void sentenceSplitterUsesUtilityProfileAbbreviations() {
        assertThat(MultilingualSentenceSplitter.split(
                "Dr. Müller prüft Art. 5. Danach folgt Text.",
                LanguageProfiles.forCode("de")
        )).containsExactly(
                "Dr. Müller prüft Art. 5.",
                "Danach folgt Text."
        );
    }
}
