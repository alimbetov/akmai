package kz.alimbetov.akmai.knowledge.chunking;

import org.springframework.stereotype.Component;

@Component
public class TokenEstimator {

    private static final double SAFETY_MARGIN = 1.05;

    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        double estimated = 0.0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            estimated += weight(codePoint);
            offset += Character.charCount(codePoint);
        }

        return Math.max(1, (int) Math.ceil(estimated * SAFETY_MARGIN));
    }

    private double weight(int codePoint) {
        if (Character.isWhitespace(codePoint)) {
            return 0.05;
        }

        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        if (script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL) {
            return 1.0;
        }

        if (Character.isLetterOrDigit(codePoint)) {
            if (script == Character.UnicodeScript.CYRILLIC
                    || script == Character.UnicodeScript.GREEK) {
                return 0.38;
            }
            return 0.30;
        }

        if (Character.getType(codePoint) == Character.OTHER_SYMBOL
                || Character.isSupplementaryCodePoint(codePoint)) {
            return 1.0;
        }

        return 0.45;
    }
}
