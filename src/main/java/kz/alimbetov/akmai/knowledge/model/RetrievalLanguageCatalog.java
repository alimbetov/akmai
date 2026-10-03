package kz.alimbetov.akmai.knowledge.model;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class RetrievalLanguageCatalog {

    public static final String UNKNOWN = "unknown";

    private static final List<String> CODES = List.of(
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
            UNKNOWN
    );

    private static final Set<String> CODE_SET = Set.copyOf(CODES);

    private RetrievalLanguageCatalog() {
    }

    public static List<String> codes() {
        return CODES;
    }

    public static String normalizeOrUnknown(String language) {
        if (language == null || language.isBlank()) {
            return UNKNOWN;
        }

        String normalized = language.trim()
                .toLowerCase(Locale.ROOT);
        return CODE_SET.contains(normalized)
                ? normalized
                : UNKNOWN;
    }

    public static boolean isSupported(String language) {
        return language != null
                && CODE_SET.contains(
                        language.trim().toLowerCase(Locale.ROOT)
                );
    }
}
