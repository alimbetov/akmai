package kz.alimbetov.akmai.knowledge.chunking;

import java.util.Set;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;

public record LanguageProfile(
        KnowledgeLanguage language,
        Set<String> abbreviations,
        Set<Character> terminalChars
) {

    public LanguageProfile {
        if (language == null) {
            throw new IllegalArgumentException("language must not be null");
        }
        abbreviations = Set.copyOf(
                abbreviations == null ? Set.of() : abbreviations
        );
        terminalChars = Set.copyOf(
                terminalChars == null ? Set.of() : terminalChars
        );
    }

    public String code() {
        return language.code();
    }
}
