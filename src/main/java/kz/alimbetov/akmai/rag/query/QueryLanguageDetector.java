package kz.alimbetov.akmai.rag.query;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class QueryLanguageDetector {

    private static final Set<Character> KAZAKH_LETTERS =
            Set.of('ә', 'ғ', 'қ', 'ң', 'ө', 'ұ', 'ү', 'һ', 'і');

    public String detect(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (value.codePoints().anyMatch(this::isHan)) {
            return "zh";
        }
        if (value.chars().mapToObj(c -> (char) c).anyMatch(KAZAKH_LETTERS::contains)) {
            return "kk";
        }
        if (value.codePoints().anyMatch(this::isCyrillic)) {
            return "ru";
        }
        if (value.codePoints().anyMatch(this::isLatin)) {
            return "en";
        }
        return "unknown";
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
