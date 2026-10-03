package kz.alimbetov.akmai.knowledge.projection;

import java.util.Locale;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;

public enum LexicalSearchLanguage {
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

    public static LexicalSearchLanguage from(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(KnowledgeLanguage.parse(value).name());
        } catch (IllegalArgumentException exception) {
            return UNKNOWN;
        }
    }
}
