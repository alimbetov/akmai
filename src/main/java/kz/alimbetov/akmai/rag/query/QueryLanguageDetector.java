package kz.alimbetov.akmai.rag.query;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class QueryLanguageDetector {

    private static final Set<Character> KAZAKH_LETTERS =
            Set.of('ә', 'ғ', 'қ', 'ң', 'ө', 'ұ', 'ү', 'һ', 'і');

    public String detect(String text) {
        return decision(text).primary();
    }

    public LanguageDecision decision(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (value.codePoints().anyMatch(this::isHan)) {
            return new LanguageDecision("zh", List.of("zh"), 0.99);
        }
        if (value.chars().mapToObj(c -> (char) c).anyMatch(KAZAKH_LETTERS::contains)) {
            return new LanguageDecision("kk", List.of("kk"), 0.98);
        }
        if (value.codePoints().anyMatch(this::isCyrillic)) {
            return new LanguageDecision("unknown", List.of("kk", "ru"), 0.5);
        }
        if (value.codePoints().anyMatch(this::isLatin)) {
            return new LanguageDecision("en", List.of("en"), 0.9);
        }
        return new LanguageDecision("unknown", List.of(), 0.0);
    }

    private boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private boolean isCyrillic(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.CYRILLIC;
    }

    private boolean isLatin(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN;
    }
}
