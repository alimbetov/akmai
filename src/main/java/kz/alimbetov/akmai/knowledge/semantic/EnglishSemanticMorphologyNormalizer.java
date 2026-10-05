package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class EnglishSemanticMorphologyNormalizer
        implements SemanticMorphologyNormalizer {

    private static final Set<String> IRREGULAR_PLURALS = Set.of(
            "data",
            "media",
            "series",
            "species"
    );

    @Override
    public String language() {
        return "en";
    }

    @Override
    public List<String> normalizeTokens(String text) {
        String normalized =
                EnglishSemanticConceptCatalog.normalizePhrase(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        return List.of(normalized.split("\\s+"));
    }

    @Override
    public List<String> lemmaTokens(String text) {
        return normalizeTokens(text).stream()
                .map(this::lemma)
                .toList();
    }

    @Override
    public List<String> stemTokens(String text) {
        return lemmaTokens(text).stream()
                .map(this::stem)
                .toList();
    }

    private String lemma(String token) {
        String value = token.toLowerCase(Locale.ROOT);
        if (value.length() <= 3 || IRREGULAR_PLURALS.contains(value)) {
            return value;
        }
        if (value.endsWith("ies") && value.length() > 4) {
            return value.substring(0, value.length() - 3) + "y";
        }
        if (value.endsWith("sses")) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("xes")
                || value.endsWith("zes")
                || value.endsWith("ches")
                || value.endsWith("shes")) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("s")
                && !value.endsWith("ss")
                && !value.endsWith("us")
                && !value.endsWith("is")) {
            return value.substring(0, value.length() - 1);
        }
        if (value.endsWith("ied") && value.length() > 4) {
            return value.substring(0, value.length() - 3) + "y";
        }
        if (value.endsWith("ing") && value.length() > 5) {
            return undouble(value.substring(0, value.length() - 3));
        }
        if (value.endsWith("ed") && value.length() > 4) {
            return undouble(value.substring(0, value.length() - 2));
        }
        return value;
    }

    private String stem(String value) {
        String token = value;
        if (token.length() <= 4) {
            return token;
        }
        if (token.endsWith("ization") && token.length() > 9) {
            return token.substring(0, token.length() - 7);
        }
        if (token.endsWith("ation") && token.length() > 7) {
            return token.substring(0, token.length() - 5);
        }
        if (token.endsWith("tion") && token.length() > 6) {
            return token.substring(0, token.length() - 3);
        }
        if (token.endsWith("ment") && token.length() > 6) {
            return token.substring(0, token.length() - 4);
        }
        if (token.endsWith("ness") && token.length() > 6) {
            return token.substring(0, token.length() - 4);
        }
        if (token.endsWith("ity") && token.length() > 6) {
            return token.substring(0, token.length() - 3);
        }
        if (token.endsWith("ive") && token.length() > 6) {
            return token.substring(0, token.length() - 3);
        }
        if (token.endsWith("al") && token.length() > 5) {
            return token.substring(0, token.length() - 2);
        }
        return token;
    }

    private String undouble(String value) {
        if (value.length() < 3) {
            return value;
        }
        char last = value.charAt(value.length() - 1);
        char previous = value.charAt(value.length() - 2);
        if (last == previous
                && last != 's'
                && last != 'l'
                && last != 'z') {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
