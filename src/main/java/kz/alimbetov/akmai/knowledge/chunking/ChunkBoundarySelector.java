package kz.alimbetov.akmai.knowledge.chunking;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class ChunkBoundarySelector {

    static final int RANK_NONE = 0;
    static final int RANK_WHITESPACE = 1;
    static final int RANK_COMMA = 2;
    static final int RANK_CLAUSE = 3;
    static final int RANK_SENTENCE = 4;
    static final int RANK_STRUCTURAL = 5;

    private static final Pattern LIST_ITEM = Pattern.compile(
            "^(?:[-–—•▪◦]|\\[\\d{1,4}]|\\(?\\d{1,4}[.)]|"
                    + "\\(?\\p{L}[.)]|\\(?[IVXLCDMivxlcdm]{1,8}[.)])\\s+"
    );

    private ChunkBoundarySelector() {
    }

    static int bestBoundary(
            String text,
            int minimumIndex,
            int maximumIndex,
            String language
    ) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int min = safeBoundary(text, Math.max(1, minimumIndex));
        int max = safeBoundary(text, Math.min(maximumIndex, text.length()));
        if (min > max) {
            min = max;
        }

        for (int expectedRank = RANK_STRUCTURAL;
                expectedRank >= RANK_WHITESPACE;
                expectedRank--) {
            for (int index = max; index >= min; index--) {
                int safe = safeBoundary(text, index);
                if (safe != index || safe <= 0 || safe >= text.length()) {
                    continue;
                }
                if (rank(text, safe, language) == expectedRank) {
                    return safe;
                }
            }
        }
        return max;
    }

    static int rank(String text, int index, String language) {
        if (text == null || text.isEmpty()
                || index <= 0 || index >= text.length()) {
            return RANK_NONE;
        }
        if (safeBoundary(text, index) != index) {
            return RANK_NONE;
        }

        int left = previousNonWhitespace(text, index - 1);
        int right = nextNonWhitespace(text, index);
        if (left < 0 || right >= text.length()) {
            return RANK_NONE;
        }

        String gap = text.substring(left + 1, right);
        if (gap.contains("\n\n") || startsListItem(text, right)) {
            return RANK_STRUCTURAL;
        }

        int terminalIndex = terminalIndex(text, left);
        int terminal = text.codePointAt(terminalIndex);
        LanguageProfile profile = LanguageProfiles.forCode(language);
        if (profile.terminalChars().contains((char) terminal)
                && isSentenceTerminal(text, terminalIndex, right, profile)) {
            return RANK_SENTENCE;
        }

        if (terminal == ';' || terminal == ':'
                || terminal == '；' || terminal == '：'
                || terminal == '—') {
            return RANK_CLAUSE;
        }
        if (terminal == ',' || terminal == '，') {
            return RANK_COMMA;
        }
        if (!gap.isEmpty()
                || Character.isWhitespace(text.codePointBefore(index))) {
            return RANK_WHITESPACE;
        }
        return RANK_NONE;
    }

    private static boolean isSentenceTerminal(
            String text,
            int terminalIndex,
            int right,
            LanguageProfile profile
    ) {
        int terminal = text.codePointAt(terminalIndex);
        if (terminal == '.') {
            int before = previousNonWhitespace(text, terminalIndex - 1);
            if (before >= 0
                    && Character.isDigit(text.codePointAt(before))
                    && right < text.length()
                    && Character.isDigit(text.codePointAt(right))) {
                return false;
            }
            if (isKnownAbbreviation(text, terminalIndex, profile.abbreviations())) {
                return false;
            }
        }
        return hasSeparatorAfterTerminal(text, terminalIndex, right);
    }

    private static boolean hasSeparatorAfterTerminal(
            String text,
            int terminalIndex,
            int right
    ) {
        int after = terminalIndex + Character.charCount(text.codePointAt(terminalIndex));
        while (after < right) {
            int codePoint = text.codePointAt(after);
            if (!isClosingPunctuation(codePoint)
                    && !Character.isWhitespace(codePoint)) {
                return false;
            }
            after += Character.charCount(codePoint);
        }
        return right > terminalIndex;
    }

    private static int terminalIndex(String text, int left) {
        int index = left;
        while (index >= 0) {
            int codePoint = text.codePointAt(index);
            if (!isClosingPunctuation(codePoint)) {
                return index;
            }
            if (index == 0) {
                return 0;
            }
            index = text.offsetByCodePoints(index, -1);
        }
        return left;
    }

    private static boolean isClosingPunctuation(int codePoint) {
        return codePoint == '"' || codePoint == '\''
                || codePoint == '”' || codePoint == '’'
                || codePoint == '»' || codePoint == ')'
                || codePoint == ']' || codePoint == '}';
    }

    private static boolean isKnownAbbreviation(
            String text,
            int terminalIndex,
            Set<String> abbreviations
    ) {
        if (abbreviations.isEmpty()) {
            return false;
        }
        int start = Math.max(0, terminalIndex - 24);
        String left = text.substring(start, terminalIndex + 1)
                .toLowerCase(Locale.ROOT);
        return abbreviations.stream().anyMatch(left::endsWith);
    }

    private static boolean startsListItem(String text, int index) {
        if (index <= 0 || index >= text.length()) {
            return false;
        }
        int lineStart = index;
        while (lineStart > 0) {
            int previous = text.offsetByCodePoints(lineStart, -1);
            int codePoint = text.codePointAt(previous);
            if (codePoint == '\n' || codePoint == '\r') {
                break;
            }
            if (!Character.isWhitespace(codePoint)) {
                return false;
            }
            lineStart = previous;
        }
        int end = Math.min(text.length(), index + 24);
        return LIST_ITEM.matcher(text.substring(index, end)).find();
    }

    private static int previousNonWhitespace(String text, int from) {
        int index = Math.min(from, text.length() - 1);
        while (index >= 0) {
            int codePoint = text.codePointAt(index);
            if (!Character.isWhitespace(codePoint)) {
                return index;
            }
            if (index == 0) {
                return -1;
            }
            index = text.offsetByCodePoints(index, -1);
        }
        return -1;
    }

    private static int nextNonWhitespace(String text, int from) {
        int index = Math.max(0, from);
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            if (!Character.isWhitespace(codePoint)) {
                return index;
            }
            index += Character.charCount(codePoint);
        }
        return text.length();
    }

    private static int safeBoundary(String text, int index) {
        int bounded = Math.max(0, Math.min(index, text.length()));
        if (bounded > 0
                && bounded < text.length()
                && Character.isHighSurrogate(text.charAt(bounded - 1))
                && Character.isLowSurrogate(text.charAt(bounded))) {
            return bounded - 1;
        }
        return bounded;
    }
}
