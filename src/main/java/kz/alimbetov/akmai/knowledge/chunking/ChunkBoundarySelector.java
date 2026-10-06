package kz.alimbetov.akmai.knowledge.chunking;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;

/**
 * Unicode-safe, language-aware chunk boundary ranker.
 *
 * <p>Token limits are hard constraints handled by the caller. This selector only chooses
 * the semantically safest boundary inside the already valid [minimum, maximum] window.</p>
 */
final class ChunkBoundarySelector {

    static final int RANK_NONE = 0;
    static final int RANK_WHITESPACE = 1;
    static final int RANK_COMMA = 2;
    static final int RANK_CLAUSE = 3;
    static final int RANK_SENTENCE = 4;
    static final int RANK_STRUCTURAL = 5;

    private static final Pattern LIST_ITEM = Pattern.compile(
            "^(?:[-–—•▪◦]|\\[\\d{1,4}]|\\(?\\d{1,4}[.)、）]|"
                    + "\\(?\\p{L}[.)]|\\(?[IVXLCDMivxlcdm]{1,8}[.)])\\s*"
    );
    private static final Pattern CJK_STRUCTURAL_ITEM = Pattern.compile(
            "^(?:第[一二三四五六七八九十百千〇零两\\d]{1,12}[章节条款项]|"
                    + "[（(]?[一二三四五六七八九十百千〇零两]{1,8}[）)、.]|"
                    + "\\d{1,4}[、.)）])"
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

        LanguageProfile profile = LanguageProfiles.forCode(language);
        String gap = text.substring(left + 1, right);
        if (gap.contains("\n\n") || startsStructuralItem(text, right, profile)) {
            return RANK_STRUCTURAL;
        }

        int terminalIndex = terminalIndex(text, left);
        int terminal = text.codePointAt(terminalIndex);
        if (profile.terminalChars().contains((char) terminal)
                && isSentenceTerminal(text, terminalIndex, right, profile)) {
            return RANK_SENTENCE;
        }

        if (profile.clauseChars().contains((char) terminal)
                && !isProtectedClausePunctuation(text, terminalIndex)) {
            return RANK_CLAUSE;
        }
        if (profile.weakChars().contains((char) terminal)
                && !isNumericSeparator(text, terminalIndex)) {
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
        if (isInsideTerminalCluster(text, terminalIndex, profile)) {
            return false;
        }
        if (terminal == '.' && isProtectedPeriod(text, terminalIndex, profile)) {
            return false;
        }
        return hasSeparatorAfterTerminal(text, terminalIndex, right);
    }

    private static boolean isProtectedPeriod(
            String text,
            int terminalIndex,
            LanguageProfile profile
    ) {
        if (isNumericSeparator(text, terminalIndex)) {
            return true;
        }
        if (isInternalTokenPeriod(text, terminalIndex)) {
            return true;
        }
        if (isKnownAbbreviation(text, terminalIndex, profile.abbreviations())) {
            return true;
        }
        return isSingleLetterInitial(text, terminalIndex);
    }

    private static boolean isInsideTerminalCluster(
            String text,
            int terminalIndex,
            LanguageProfile profile
    ) {
        int next = terminalIndex + Character.charCount(text.codePointAt(terminalIndex));
        return next < text.length()
                && profile.terminalChars().contains(text.charAt(next));
    }

    private static boolean isInternalTokenPeriod(String text, int index) {
        if (index <= 0 || index + 1 >= text.length()) {
            return false;
        }
        int before = text.codePointBefore(index);
        int afterIndex = index + Character.charCount(text.codePointAt(index));
        if (afterIndex >= text.length()) {
            return false;
        }
        int after = text.codePointAt(afterIndex);
        return Character.isLetterOrDigit(before)
                && Character.isLetterOrDigit(after);
    }

    private static boolean isSingleLetterInitial(String text, int periodIndex) {
        if (periodIndex <= 0) {
            return false;
        }
        int letterIndex = text.offsetByCodePoints(periodIndex, -1);
        int letter = text.codePointAt(letterIndex);
        if (!Character.isLetter(letter)) {
            return false;
        }
        if (letterIndex == 0) {
            return true;
        }
        int previousIndex = text.offsetByCodePoints(letterIndex, -1);
        int previous = text.codePointAt(previousIndex);
        return Character.isWhitespace(previous) || isOpeningPunctuation(previous);
    }

    private static boolean isNumericSeparator(String text, int index) {
        if (index <= 0 || index + 1 >= text.length()) {
            return false;
        }
        int before = text.codePointBefore(index);
        int afterIndex = index + Character.charCount(text.codePointAt(index));
        if (afterIndex >= text.length()) {
            return false;
        }
        int after = text.codePointAt(afterIndex);
        return Character.isDigit(before) && Character.isDigit(after);
    }

    private static boolean isProtectedClausePunctuation(String text, int index) {
        int punctuation = text.codePointAt(index);
        if (punctuation != ':') {
            return false;
        }
        if (isNumericSeparator(text, index)) {
            return true;
        }
        int after = index + Character.charCount(punctuation);
        return after < text.length()
                && (text.charAt(after) == '/' || text.charAt(after) == '\\');
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
                || codePoint == ']' || codePoint == '}'
                || codePoint == '】' || codePoint == '》'
                || codePoint == '）';
    }

    private static boolean isOpeningPunctuation(int codePoint) {
        return codePoint == '"' || codePoint == '\''
                || codePoint == '“' || codePoint == '‘'
                || codePoint == '«' || codePoint == '('
                || codePoint == '[' || codePoint == '{'
                || codePoint == '【' || codePoint == '《'
                || codePoint == '（';
    }

    private static boolean isKnownAbbreviation(
            String text,
            int terminalIndex,
            Set<String> abbreviations
    ) {
        if (abbreviations.isEmpty()) {
            return false;
        }
        int start = Math.max(0, terminalIndex - 32);
        String left = text.substring(start, terminalIndex + 1)
                .toLowerCase(Locale.ROOT);
        return abbreviations.stream().anyMatch(left::endsWith);
    }

    private static boolean startsStructuralItem(
            String text,
            int index,
            LanguageProfile profile
    ) {
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

        int lineEnd = index;
        while (lineEnd < text.length()) {
            int codePoint = text.codePointAt(lineEnd);
            if (codePoint == '\n' || codePoint == '\r') {
                break;
            }
            lineEnd += Character.charCount(codePoint);
        }
        String line = text.substring(index, lineEnd).stripLeading();
        if (line.isEmpty()) {
            return false;
        }
        String prefix = line.substring(0, Math.min(line.length(), 64));
        if (LIST_ITEM.matcher(prefix).find()) {
            return true;
        }
        if (profile.language() == KnowledgeLanguage.ZH
                && CJK_STRUCTURAL_ITEM.matcher(prefix).find()) {
            return true;
        }

        String normalized = prefix.toLowerCase(Locale.ROOT);
        for (String keyword : profile.structuralKeywords()) {
            if (matchesStructuralKeyword(normalized, keyword, profile)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesStructuralKeyword(
            String line,
            String keyword,
            LanguageProfile profile
    ) {
        if (keyword.equals("§")) {
            return line.startsWith("§");
        }
        if (profile.language() == KnowledgeLanguage.ZH) {
            return line.startsWith(keyword);
        }
        if (startsKeyword(line, keyword)) {
            return true;
        }

        int cursor = 0;
        while (cursor < line.length()) {
            int codePoint = line.codePointAt(cursor);
            if (Character.isDigit(codePoint)
                    || Character.isWhitespace(codePoint)
                    || codePoint == '(' || codePoint == ')'
                    || codePoint == '（' || codePoint == '）'
                    || codePoint == '.' || codePoint == ':'
                    || codePoint == '-' || codePoint == '–'
                    || codePoint == '—' || codePoint == '№') {
                cursor += Character.charCount(codePoint);
                continue;
            }
            break;
        }
        return cursor > 0 && startsKeyword(line.substring(cursor), keyword);
    }

    private static boolean startsKeyword(String line, String keyword) {
        String candidate = line.stripLeading();
        if (!candidate.startsWith(keyword)) {
            return false;
        }
        if (candidate.length() == keyword.length()) {
            return true;
        }
        int next = candidate.codePointAt(keyword.length());
        return Character.isWhitespace(next)
                || Character.isDigit(next)
                || next == '.' || next == ':' || next == '№'
                || next == '-' || next == '–' || next == '—';
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
