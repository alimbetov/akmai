package kz.alimbetov.akmai.knowledge.chunking;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;

public final class LanguageProfiles {

    private static final Set<Character> COMMON_TERMINALS =
            Set.of('.', '!', '?', '。', '！', '？', '…', ';');

    private static final Map<KnowledgeLanguage, LanguageProfile> PROFILES =
            buildProfiles();

    private LanguageProfiles() {
    }

    public static LanguageProfile forCode(String rawLanguage) {
        if (rawLanguage == null || rawLanguage.isBlank()) {
            return forLanguage(KnowledgeLanguage.UNKNOWN);
        }
        try {
            return forLanguage(KnowledgeLanguage.parse(rawLanguage));
        } catch (IllegalArgumentException exception) {
            return forLanguage(KnowledgeLanguage.UNKNOWN);
        }
    }

    public static LanguageProfile forLanguage(
            KnowledgeLanguage language
    ) {
        KnowledgeLanguage resolved = language == null
                ? KnowledgeLanguage.UNKNOWN
                : language;
        return PROFILES.get(resolved);
    }

    private static Map<KnowledgeLanguage, LanguageProfile> buildProfiles() {
        EnumMap<KnowledgeLanguage, LanguageProfile> profiles =
                new EnumMap<>(KnowledgeLanguage.class);

        register(profiles, KnowledgeLanguage.EN, Set.of(
                "mr.", "mrs.", "ms.", "dr.", "prof.", "etc.",
                "e.g.", "i.e.", "vs.", "fig.", "no.", "sec.", "art."
        ));
        register(profiles, KnowledgeLanguage.RU, Set.of(
                "г.", "гг.", "т.е.", "т.д.", "т.п.", "др.", "стр.",
                "рис.", "им.", "см.", "напр.", "п.", "пп.", "ст.",
                "табл.", "разд."
        ));
        register(profiles, KnowledgeLanguage.KK, Set.of(
                "ж.", "т.б.", "т.с.с.", "мыс.", "ст.", "п."
        ));
        register(profiles, KnowledgeLanguage.DE, Set.of(
                "dr.", "prof.", "hr.", "fr.", "bzw.", "z.b.", "u.a.",
                "ziff.", "abs.", "art."
        ));
        register(profiles, KnowledgeLanguage.FR, Set.of(
                "m.", "mme.", "mlle.", "dr.", "pr.", "art.", "etc."
        ));
        register(profiles, KnowledgeLanguage.ES, Set.of(
                "sr.", "sra.", "srta.", "dr.", "dra.", "ud.", "uds.",
                "art.", "etc."
        ));
        register(profiles, KnowledgeLanguage.PT, Set.of(
                "sr.", "sra.", "dr.", "dra.", "prof.", "art.", "etc."
        ));
        register(profiles, KnowledgeLanguage.IT, Set.of(
                "dott.", "dott.ssa.", "sig.", "sig.ra.", "prof.",
                "art.", "ecc."
        ));
        register(profiles, KnowledgeLanguage.TR, Set.of(
                "dr.", "prof.", "sn.", "vb.", "vs.", "md."
        ));
        register(
                profiles,
                KnowledgeLanguage.EL,
                Set.of("δρ.", "κ.", "κα.", "αρ."),
                union(COMMON_TERMINALS, Set.of(';'))
        );
        register(profiles, KnowledgeLanguage.ZH, Set.of());
        register(profiles, KnowledgeLanguage.UNKNOWN, Set.of());

        return Map.copyOf(profiles);
    }

    private static void register(
            Map<KnowledgeLanguage, LanguageProfile> profiles,
            KnowledgeLanguage language,
            Set<String> abbreviations
    ) {
        register(profiles, language, abbreviations, COMMON_TERMINALS);
    }

    private static void register(
            Map<KnowledgeLanguage, LanguageProfile> profiles,
            KnowledgeLanguage language,
            Set<String> abbreviations,
            Set<Character> terminals
    ) {
        profiles.put(
                language,
                new LanguageProfile(language, abbreviations, terminals)
        );
    }

    private static <T> Set<T> union(Set<T> left, Set<T> right) {
        java.util.LinkedHashSet<T> values =
                new java.util.LinkedHashSet<>(left);
        values.addAll(right);
        return Set.copyOf(values);
    }
}
