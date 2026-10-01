package kz.alimbetov.akmai.knowledge.model;

import java.util.Locale;

public enum KnowledgeLanguage {
    KK,
    RU,
    EN,
    ZH;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static KnowledgeLanguage parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("language is required");
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "kk", "kaz", "kazakh" -> KK;
            case "ru", "rus", "russian" -> RU;
            case "en", "eng", "english" -> EN;
            case "zh", "zho", "chi", "chinese" -> ZH;
            default -> throw new IllegalArgumentException("Unsupported language: " + raw);
        };
    }
}
