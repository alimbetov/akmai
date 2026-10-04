package kz.alimbetov.akmai.knowledge.model;

import java.util.Locale;
import java.util.Set;

public final class DocumentMetadata {

    public static final String ACCESS_LEVEL = "access_level";

    private static final Set<String> RUNTIME_CONTROL_KEYS = Set.of(
            ACCESS_LEVEL,
            "authority",
            "authoritytier",
            "querychunkid",
            "generation",
            "score",
            "rerankscore",
            "reranksemanticscore",
            "expansion",
            "adaptivegraphband",
            "adaptivegraphscore",
            "adaptivegraphcontributingedges"
    );

    private DocumentMetadata() {
    }

    public static boolean isReservedUserKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.startsWith("akmai")
                || RUNTIME_CONTROL_KEYS.contains(normalized);
    }
}
