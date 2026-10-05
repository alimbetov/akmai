package kz.alimbetov.akmai.knowledge.semantic;

import java.util.Comparator;
import java.util.List;

abstract class SuffixSemanticMorphologyNormalizer
        implements SemanticMorphologyNormalizer {

    private final String language;
    private final List<String> lemmaSuffixes;
    private final List<String> stemSuffixes;

    SuffixSemanticMorphologyNormalizer(
            String language,
            List<String> lemmaSuffixes,
            List<String> stemSuffixes
    ) {
        this.language = language;
        this.lemmaSuffixes = longestFirst(lemmaSuffixes);
        this.stemSuffixes = longestFirst(stemSuffixes);
    }

    @Override
    public String language() {
        return language;
    }

    @Override
    public List<String> normalizeTokens(String text) {
        return SlavicTurkicTextNormalizer.tokens(text);
    }

    @Override
    public List<String> lemmaTokens(String text) {
        return normalizeTokens(text).stream()
                .map(token -> strip(token, lemmaSuffixes, 4))
                .toList();
    }

    @Override
    public List<String> stemTokens(String text) {
        return lemmaTokens(text).stream()
                .map(token -> strip(token, stemSuffixes, 4))
                .toList();
    }

    private String strip(
            String token,
            List<String> suffixes,
            int minimumRemaining
    ) {
        if (token.length() <= minimumRemaining + 1) {
            return token;
        }
        for (String suffix : suffixes) {
            if (token.endsWith(suffix)
                    && token.length() - suffix.length()
                            >= minimumRemaining) {
                return token.substring(
                        0,
                        token.length() - suffix.length()
                );
            }
        }
        return token;
    }

    private List<String> longestFirst(List<String> suffixes) {
        return suffixes.stream()
                .distinct()
                .sorted(
                        Comparator.comparingInt(String::length)
                                .reversed()
                )
                .toList();
    }
}
