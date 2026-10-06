package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
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
    void everySupportedLanguageHasExplicitSmartDelimiterProfile() {
        for (KnowledgeLanguage language : KnowledgeLanguage.values()) {
            LanguageProfile profile = LanguageProfiles.forLanguage(language);
            assertThat(profile.terminalChars())
                    .as("terminal chars for %s", language)
                    .isNotEmpty();
            assertThat(profile.clauseChars())
                    .as("clause chars for %s", language)
                    .isNotEmpty();
            assertThat(profile.weakChars())
                    .as("weak chars for %s", language)
                    .isNotEmpty();
            if (language != KnowledgeLanguage.UNKNOWN) {
                assertThat(profile.structuralKeywords())
                        .as("structural keywords for %s", language)
                        .isNotEmpty();
            }
        }
    }

    @Test
    void continentalProfilesProtectDecimalCommaSemantics() {
        Set<KnowledgeLanguage> decimalCommaLanguages = Set.of(
                KnowledgeLanguage.KK,
                KnowledgeLanguage.RU,
                KnowledgeLanguage.DE,
                KnowledgeLanguage.FR,
                KnowledgeLanguage.ES,
                KnowledgeLanguage.PT,
                KnowledgeLanguage.IT,
                KnowledgeLanguage.TR,
                KnowledgeLanguage.EL
        );

        for (KnowledgeLanguage language : KnowledgeLanguage.values()) {
            assertThat(LanguageProfiles.forLanguage(language).decimalComma())
                    .as("decimal-comma policy for %s", language)
                    .isEqualTo(decimalCommaLanguages.contains(language));
        }
    }

    @Test
    void unsupportedLanguageUsesNeutralProfileInsteadOfEnglish() {
        assertThat(LanguageProfiles.forCode("ja").language())
                .isEqualTo(KnowledgeLanguage.UNKNOWN);
        assertThat(LanguageProfiles.forCode("ja").abbreviations())
                .isEmpty();
        assertThat(LanguageProfiles.forCode("ja").structuralKeywords())
                .isEmpty();
    }

    @Test
    void greekProfileKeepsSemicolonAsSentenceTerminal() {
        assertThat(LanguageProfiles.forCode("el").terminalChars())
                .contains(';', ';');
    }

    @Test
    void chineseProfileKeepsIdeographicEnumerationDelimiter() {
        assertThat(LanguageProfiles.forCode("zh").weakChars())
                .contains('、', '，');
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
