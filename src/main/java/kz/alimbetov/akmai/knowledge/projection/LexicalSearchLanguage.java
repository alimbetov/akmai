package kz.alimbetov.akmai.knowledge.projection;

public enum LexicalSearchLanguage {
    KK,
    RU,
    EN,
    ZH,
    UNKNOWN;

    public static LexicalSearchLanguage from(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        return switch (value.toLowerCase()) {
            case "kk", "kaz", "kazakh" -> KK;
            case "ru", "rus", "russian" -> RU;
            case "en", "eng", "english" -> EN;
            case "zh", "zho", "chi", "chinese" -> ZH;
            default -> UNKNOWN;
        };
    }
}
