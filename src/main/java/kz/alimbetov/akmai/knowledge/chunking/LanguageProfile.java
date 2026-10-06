package kz.alimbetov.akmai.knowledge.chunking;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;

/**
 * Language-specific punctuation and structural hints used by chunk-boundary selection.
 *
 * <p>The profile deliberately contains only cheap deterministic signals. Token budgeting
 * remains the responsibility of {@link TokenEstimator}; this profile decides which legal
 * boundary is preferable inside an already-safe budget window.</p>
 */
public record LanguageProfile(
        KnowledgeLanguage language,
        Set<String> abbreviations,
        Set<Character> terminalChars,
        Set<Character> clauseChars,
        Set<Character> weakChars,
        Set<String> structuralKeywords,
        boolean decimalComma
) {

    private static final Set<Character> DEFAULT_CLAUSE_CHARS =
            Set.of(';', ':', '；', '：', '—');
    private static final Set<Character> DEFAULT_WEAK_CHARS =
            Set.of(',', '，');

    public LanguageProfile {
        if (language == null) {
            throw new IllegalArgumentException("language must not be null");
        }
        abbreviations = normalizeStrings(abbreviations);
        terminalChars = Set.copyOf(
                terminalChars == null ? Set.of() : terminalChars
        );
        clauseChars = Set.copyOf(
                clauseChars == null ? DEFAULT_CLAUSE_CHARS : clauseChars
        );
        weakChars = Set.copyOf(
                weakChars == null ? DEFAULT_WEAK_CHARS : weakChars
        );
        structuralKeywords = normalizeStrings(structuralKeywords);
    }

    /** Backward-compatible constructor for utility callers and tests. */
    public LanguageProfile(
            KnowledgeLanguage language,
            Set<String> abbreviations,
            Set<Character> terminalChars
    ) {
        this(
                language,
                abbreviations,
                terminalChars,
                DEFAULT_CLAUSE_CHARS,
                DEFAULT_WEAK_CHARS,
                Set.of(),
                false
        );
    }

    public String code() {
        return language.code();
    }

    public boolean isDecimalSeparator(int codePoint) {
        return codePoint == '.' || (decimalComma && codePoint == ',');
    }

    private static Set<String> normalizeStrings(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
