package kz.alimbetov.akmai.knowledge.model;

import java.util.Locale;

public enum KnowledgeLanguage {
    KK,
    RU,
    EN,
    ZH,
    DE,
    FR,
    ES,
    PT,
    IT,
    TR,
    EL,
    UNKNOWN;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static KnowledgeLanguage parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("language is required");
        }

        String value = raw.trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
        if (value.isBlank()) {
            throw new IllegalArgumentException("language is required");
        }

        return switch (value) {
            case "kaz", "kazakh", "қазақ", "қазақша" -> KK;
            case "rus", "russian", "русский" -> RU;
            case "eng", "english" -> EN;
            case "zho", "chi", "chinese", "中文" -> ZH;
            case "deu", "ger", "german", "deutsch" -> DE;
            case "fra", "fre", "french", "français", "francais" -> FR;
            case "spa", "spanish", "español", "espanol" -> ES;
            case "por", "portuguese", "português", "portugues" -> PT;
            case "ita", "italian", "italiano" -> IT;
            case "tur", "turkish", "türkçe", "turkce" -> TR;
            case "ell", "gre", "greek", "ελληνικά", "ελληνικα" -> EL;
            case "unknown", "und" -> UNKNOWN;
            default -> parsePrimary(value, raw);
        };
    }

    private static KnowledgeLanguage parsePrimary(
            String value,
            String raw
    ) {
        String primary = value.contains("-")
                ? value.substring(0, value.indexOf('-'))
                : value;
        return switch (primary) {
            case "kk" -> KK;
            case "ru" -> RU;
            case "en" -> EN;
            case "zh" -> ZH;
            case "de" -> DE;
            case "fr" -> FR;
            case "es" -> ES;
            case "pt" -> PT;
            case "it" -> IT;
            case "tr" -> TR;
            case "el" -> EL;
            default -> throw new IllegalArgumentException(
                    "Unsupported language: " + raw
            );
        };
    }
}
