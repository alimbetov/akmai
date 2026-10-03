package kz.alimbetov.akmai.knowledge.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class MultilingualSentenceSplitter {

    private static final Map<String, Set<String>> ABBREVIATIONS = Map.ofEntries(
            Map.entry(
                    "en",
                    Set.of(
                            "mr.", "mrs.", "ms.", "dr.", "prof.", "etc.",
                            "e.g.", "i.e.", "vs.", "fig.", "no.", "sec.",
                            "art."
                    )
            ),
            Map.entry(
                    "ru",
                    Set.of(
                            "г.", "гг.", "т.е.", "т.д.", "т.п.", "др.",
                            "стр.", "рис.", "им.", "см.", "напр.", "п.",
                            "пп.", "ст.", "табл.", "разд."
                    )
            ),
            Map.entry(
                    "kk",
                    Set.of(
                            "ж.", "т.б.", "т.с.с.", "мыс.", "ст.", "п."
                    )
            ),
            Map.entry(
                    "de",
                    Set.of(
                            "dr.", "prof.", "hr.", "fr.", "bzw.", "z.b.",
                            "u.a.", "ziff.", "abs.", "art."
                    )
            ),
            Map.entry(
                    "fr",
                    Set.of(
                            "m.", "mme.", "mlle.", "dr.", "pr.", "art.",
                            "etc."
                    )
            ),
            Map.entry(
                    "es",
                    Set.of(
                            "sr.", "sra.", "srta.", "dr.", "dra.", "ud.",
                            "uds.", "art.", "etc."
                    )
            ),
            Map.entry(
                    "pt",
                    Set.of(
                            "sr.", "sra.", "dr.", "dra.", "prof.", "art.",
                            "etc."
                    )
            ),
            Map.entry(
                    "it",
                    Set.of(
                            "dott.", "dott.ssa.", "sig.", "sig.ra.", "prof.",
                            "art.", "ecc."
                    )
            ),
            Map.entry(
                    "tr",
                    Set.of(
                            "dr.", "prof.", "sn.", "vb.", "vs.", "md."
                    )
            ),
            Map.entry("el", Set.of("δρ.", "κ.", "κα.", "αρ.")),
            Map.entry("zh", Set.of())
    );

    private MultilingualSentenceSplitter() {
    }

    static List<String> split(
            String text,
            String language
    ) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String code = language == null
                ? "unknown"
                : language.trim().toLowerCase(Locale.ROOT);
        Set<String> abbreviations = ABBREVIATIONS.getOrDefault(
                code,
                Set.of()
        );

        List<String> result = new ArrayList<>();
        int start = 0;
        int index = 0;

        while (index < text.length()) {
            char value = text.charAt(index);
            if (!isTerminal(value, code)) {
                index++;
                continue;
            }

            if (value == '.'
                    && periodIsProtected(text, index, abbreviations)) {
                index++;
                continue;
            }

            int end = index + 1;
            while (end < text.length()
                    && isTerminal(text.charAt(end), code)) {
                end++;
            }
            while (end < text.length()
                    && isCloser(text.charAt(end))) {
                end++;
            }

            String sentence = text.substring(start, end).trim();
            if (!sentence.isEmpty()) {
                result.add(sentence);
            }

            while (end < text.length()
                    && Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            start = end;
            index = end;
        }

        String tail = text.substring(start).trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }
        return List.copyOf(result);
    }

    private static boolean periodIsProtected(
            String text,
            int index,
            Set<String> abbreviations
    ) {
        char previous = index > 0 ? text.charAt(index - 1) : 0;
        char next = index + 1 < text.length()
                ? text.charAt(index + 1)
                : 0;

        if (Character.isLetterOrDigit(previous)
                && Character.isLetterOrDigit(next)) {
            return true;
        }

        String token = previousToken(text, index);
        if (abbreviations.contains(token)
                && nextLexicalChar(text, index) != 0) {
            return true;
        }

        String letters = token.replaceAll(
                "(?U)[^\\p{L}\\p{M}]",
                ""
        );
        if (letters.length() == 1
                && Character.isLetter(previous)
                && nextLexicalChar(text, index) != 0) {
            return true;
        }

        int tokenStart = index;
        while (tokenStart > 0
                && !Character.isWhitespace(
                        text.charAt(tokenStart - 1)
                )) {
            tokenStart--;
        }
        int tokenEnd = index + 1;
        while (tokenEnd < text.length()
                && !Character.isWhitespace(text.charAt(tokenEnd))) {
            tokenEnd++;
        }
        String full = text.substring(tokenStart, tokenEnd);
        if (index < tokenEnd - 1
                && (full.contains("://")
                    || full.contains("@")
                    || full.chars().filter(ch -> ch == '.').count() >= 2)) {
            return true;
        }

        return false;
    }

    private static String previousToken(
            String text,
            int periodIndex
    ) {
        int start = periodIndex;
        while (start > 0) {
            char value = text.charAt(start - 1);
            if (Character.isWhitespace(value)
                    || isCloser(value)
                    || isOpener(value)) {
                break;
            }
            start--;
        }
        return text.substring(start, periodIndex + 1)
                .toLowerCase(Locale.ROOT);
    }

    private static char nextLexicalChar(
            String text,
            int index
    ) {
        int cursor = index + 1;
        while (cursor < text.length()) {
            char value = text.charAt(cursor);
            if (!Character.isWhitespace(value)
                    && !isCloser(value)) {
                return value;
            }
            cursor++;
        }
        return 0;
    }

    private static boolean isTerminal(
            char value,
            String language
    ) {
        return value == '.'
                || value == '!'
                || value == '?'
                || value == '。'
                || value == '！'
                || value == '？'
                || value == '…'
                || value == ';'
                || ("el".equals(language) && value == ';');
    }

    private static boolean isCloser(char value) {
        return switch (value) {
            case ')', ']', '}', '"', '\'', '»', '”', '’', '】', '》' -> true;
            default -> false;
        };
    }

    private static boolean isOpener(char value) {
        return switch (value) {
            case '(', '[', '{', '«', '“', '‘', '【', '《' -> true;
            default -> false;
        };
    }
}
