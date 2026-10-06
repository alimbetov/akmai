package kz.alimbetov.akmai.knowledge.chunking;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;

/** Central registry for all supported multilingual chunk-boundary profiles. */
public final class LanguageProfiles {

    private static final Set<Character> COMMON_TERMINALS =
            Set.of('.', '!', '?', '。', '！', '？', '…', ';');
    private static final Set<Character> COMMON_CLAUSES =
            Set.of(';', ':', '；', '：', '—');
    private static final Set<Character> COMMON_WEAK = Set.of(',', '，');
    private static final Set<Character> ZH_WEAK = Set.of(',', '，', '、');

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

    public static LanguageProfile forLanguage(KnowledgeLanguage language) {
        KnowledgeLanguage resolved = language == null
                ? KnowledgeLanguage.UNKNOWN
                : language;
        return PROFILES.get(resolved);
    }

    private static Map<KnowledgeLanguage, LanguageProfile> buildProfiles() {
        EnumMap<KnowledgeLanguage, LanguageProfile> profiles =
                new EnumMap<>(KnowledgeLanguage.class);

        register(
                profiles,
                KnowledgeLanguage.EN,
                Set.of(
                        "mr.", "mrs.", "ms.", "dr.", "prof.", "etc.",
                        "e.g.", "i.e.", "vs.", "fig.", "no.", "sec.",
                        "art.", "para.", "ch."
                ),
                Set.of(
                        "article", "section", "chapter", "part", "clause",
                        "subsection", "paragraph", "schedule", "appendix"
                ),
                false
        );
        register(
                profiles,
                KnowledgeLanguage.RU,
                Set.of(
                        "г.", "гг.", "т.е.", "т.д.", "т.п.", "др.", "стр.",
                        "рис.", "им.", "см.", "напр.", "п.", "пп.", "ст.",
                        "табл.", "разд.", "гл.", "ч."
                ),
                Set.of(
                        "статья", "глава", "раздел", "часть", "пункт",
                        "подпункт", "приложение"
                ),
                true
        );
        register(
                profiles,
                KnowledgeLanguage.KK,
                Set.of(
                        "ж.", "т.б.", "т.с.с.", "мыс.", "ст.", "п.",
                        "б.", "тарм."
                ),
                Set.of(
                        "бап", "тарау", "бөлім", "тармақ", "тармақша",
                        "қосымша"
                ),
                true
        );
        register(
                profiles,
                KnowledgeLanguage.DE,
                Set.of(
                        "dr.", "prof.", "hr.", "fr.", "bzw.", "z.b.", "u.a.",
                        "ziff.", "abs.", "art.", "nr.", "kap."
                ),
                Set.of(
                        "§", "artikel", "abschnitt", "kapitel", "absatz",
                        "nummer", "anlage"
                ),
                true
        );
        register(
                profiles,
                KnowledgeLanguage.FR,
                Set.of(
                        "m.", "mme.", "mlle.", "dr.", "pr.", "art.", "etc.",
                        "n°", "al."
                ),
                Set.of(
                        "article", "section", "chapitre", "alinéa",
                        "paragraphe", "annexe"
                ),
                true
        );
        register(
                profiles,
                KnowledgeLanguage.ES,
                Set.of(
                        "sr.", "sra.", "srta.", "dr.", "dra.", "ud.", "uds.",
                        "art.", "etc.", "núm."
                ),
                Set.of(
                        "artículo", "sección", "capítulo", "apartado",
                        "párrafo", "anexo"
                ),
                true
        );
        register(
                profiles,
                KnowledgeLanguage.PT,
                Set.of(
                        "sr.", "sra.", "dr.", "dra.", "prof.", "art.", "etc.",
                        "n.º", "nº"
                ),
                Set.of(
                        "artigo", "seção", "secção", "capítulo", "parágrafo",
                        "inciso", "anexo"
                ),
                true
        );
        register(
                profiles,
                KnowledgeLanguage.IT,
                Set.of(
                        "dott.", "dott.ssa.", "sig.", "sig.ra.", "prof.",
                        "art.", "ecc.", "n."
                ),
                Set.of(
                        "articolo", "sezione", "capitolo", "comma",
                        "paragrafo", "allegato"
                ),
                true
        );
        register(
                profiles,
                KnowledgeLanguage.TR,
                Set.of(
                        "dr.", "prof.", "sn.", "vb.", "vs.", "md.", "bkz."
                ),
                Set.of(
                        "madde", "bölüm", "fıkra", "bent", "kısım", "ek"
                ),
                true
        );
        register(
                profiles,
                KnowledgeLanguage.EL,
                Set.of("δρ.", "κ.", "κα.", "αρ.", "άρθρ."),
                union(COMMON_TERMINALS, Set.of(';')),
                COMMON_CLAUSES,
                COMMON_WEAK,
                Set.of(
                        "άρθρο", "κεφάλαιο", "ενότητα", "παράγραφος",
                        "εδάφιο", "παράρτημα"
                ),
                true
        );
        register(
                profiles,
                KnowledgeLanguage.ZH,
                Set.of(),
                COMMON_TERMINALS,
                COMMON_CLAUSES,
                ZH_WEAK,
                Set.of("章", "条", "节", "款", "项", "附录"),
                false
        );
        register(
                profiles,
                KnowledgeLanguage.UNKNOWN,
                Set.of(),
                COMMON_TERMINALS,
                COMMON_CLAUSES,
                COMMON_WEAK,
                Set.of(),
                false
        );

        return Map.copyOf(profiles);
    }

    private static void register(
            Map<KnowledgeLanguage, LanguageProfile> profiles,
            KnowledgeLanguage language,
            Set<String> abbreviations,
            Set<String> structuralKeywords,
            boolean decimalComma
    ) {
        register(
                profiles,
                language,
                abbreviations,
                COMMON_TERMINALS,
                COMMON_CLAUSES,
                COMMON_WEAK,
                structuralKeywords,
                decimalComma
        );
    }

    private static void register(
            Map<KnowledgeLanguage, LanguageProfile> profiles,
            KnowledgeLanguage language,
            Set<String> abbreviations,
            Set<Character> terminals,
            Set<Character> clauses,
            Set<Character> weakChars,
            Set<String> structuralKeywords,
            boolean decimalComma
    ) {
        profiles.put(
                language,
                new LanguageProfile(
                        language,
                        abbreviations,
                        terminals,
                        clauses,
                        weakChars,
                        structuralKeywords,
                        decimalComma
                )
        );
    }

    private static <T> Set<T> union(Set<T> left, Set<T> right) {
        java.util.LinkedHashSet<T> values = new java.util.LinkedHashSet<>(left);
        values.addAll(right);
        return Set.copyOf(values);
    }
}
