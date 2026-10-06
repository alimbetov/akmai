package kz.alimbetov.akmai.knowledge.chunking.boundary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared Unicode-aware punctuation scanning for language profiles.
 * Concrete profiles contribute abbreviations, structural markers and language-specific punctuation.
 */
public abstract class AbstractLanguageDelimiterProfile implements LanguageDelimiterProfile {

    protected abstract Set<String> abbreviations();

    protected Set<Character> sentenceTerminators() {
        return Set.of('.', '!', '?', '。', '！', '？');
    }

    protected Set<Character> clauseTerminators() {
        return Set.of(';', ':', '；', '：');
    }

    protected int languageBonus(String text, int offset, BoundaryStrength strength) {
        return 0;
    }

    protected boolean isUnsafeSentenceBoundary(String text, int punctuationOffset) {
        char current = text.charAt(punctuationOffset);
        if (current != '.') {
            return false;
        }
        if (isDecimalPoint(text, punctuationOffset)) {
            return true;
        }
        String token = previousToken(text, punctuationOffset).toLowerCase(Locale.ROOT);
        return abbreviations().contains(token);
    }

    @Override
    public List<BoundaryCandidate> candidates(String text, int minOffset, int maxOffset) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int min = Math.max(1, minOffset);
        int max = Math.min(text.length() - 1, maxOffset);
        if (min > max) {
            return List.of();
        }
        List<BoundaryCandidate> result = new ArrayList<>();
        for (int i = min; i <= max; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                boolean paragraph = i + 1 < text.length() && text.charAt(i + 1) == '\n';
                BoundaryStrength strength = paragraph ? BoundaryStrength.PARAGRAPH : BoundaryStrength.LIST_ITEM;
                result.add(candidate(i + 1, strength, text, paragraph ? "paragraph" : "line"));
            } else if (sentenceTerminators().contains(c) && !isUnsafeSentenceBoundary(text, i)) {
                result.add(candidate(i + 1, BoundaryStrength.SENTENCE, text, "sentence"));
            } else if (clauseTerminators().contains(c)) {
                result.add(candidate(i + 1, BoundaryStrength.CLAUSE, text, "clause"));
            } else if (c == ',' || c == '，') {
                result.add(candidate(i + 1, BoundaryStrength.COMMA, text, "comma"));
            } else if (Character.isWhitespace(c)) {
                result.add(candidate(i + 1, BoundaryStrength.WHITESPACE, text, "whitespace"));
            }
        }
        return result;
    }

    private BoundaryCandidate candidate(int offset, BoundaryStrength strength, String text, String reason) {
        return new BoundaryCandidate(offset, strength, languageBonus(text, offset, strength), 0, reason);
    }

    private boolean isDecimalPoint(String text, int offset) {
        return offset > 0
                && offset + 1 < text.length()
                && Character.isDigit(text.charAt(offset - 1))
                && Character.isDigit(text.charAt(offset + 1));
    }

    private String previousToken(String text, int punctuationOffset) {
        int start = punctuationOffset - 1;
        while (start >= 0 && !Character.isWhitespace(text.charAt(start))) {
            start--;
        }
        return text.substring(start + 1, punctuationOffset + 1);
    }
}
