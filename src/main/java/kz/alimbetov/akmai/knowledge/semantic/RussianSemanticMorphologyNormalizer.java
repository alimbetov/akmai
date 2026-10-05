package kz.alimbetov.akmai.knowledge.semantic;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RussianSemanticMorphologyNormalizer
        implements SemanticMorphologyNormalizer {

    private static final List<String> INFLECTIONAL_SUFFIXES =
            List.of(
                            "иями", "ями", "ами", "его", "ого", "ему",
                            "ому", "ыми", "ими", "ией", "иям", "иях",
                            "иях", "ая", "яя", "ое", "ее", "ые", "ие",
                            "ый", "ий", "ой", "ую", "юю", "ам", "ям",
                            "ах", "ях", "ом", "ем", "ов", "ев", "ей",
                            "ы", "и", "а", "я", "у", "ю", "е", "о"
                    )
                    .stream()
                    .sorted(
                            Comparator.comparingInt(String::length)
                                    .reversed()
                    )
                    .toList();

    private static final List<String> DERIVATIONAL_SUFFIXES =
            List.of(
                            "ирование", "изация", "ический", "ическая",
                            "ические", "ического", "ность", "ности",
                            "ование", "ение", "ения", "ировать",
                            "ировать", "ческий", "ческая"
                    )
                    .stream()
                    .sorted(
                            Comparator.comparingInt(String::length)
                                    .reversed()
                    )
                    .toList();

    @Override
    public String language() {
        return "ru";
    }

    @Override
    public List<String> normalizeTokens(String text) {
        return SlavicTurkicTextNormalizer.tokens(text);
    }

    @Override
    public List<String> lemmaTokens(String text) {
        return normalizeTokens(text).stream()
                .map(this::stripInflection)
                .toList();
    }

    @Override
    public List<String> stemTokens(String text) {
        return lemmaTokens(text).stream()
                .map(this::stripDerivation)
                .toList();
    }

    private String stripInflection(String token) {
        if (token.length() <= 4) {
            return token;
        }
        for (String suffix : INFLECTIONAL_SUFFIXES) {
            if (token.endsWith(suffix)
                    && token.length() - suffix.length() >= 3) {
                return token.substring(
                        0,
                        token.length() - suffix.length()
                );
            }
        }
        return token;
    }

    private String stripDerivation(String token) {
        if (token.length() <= 5) {
            return token;
        }
        for (String suffix : DERIVATIONAL_SUFFIXES) {
            if (token.endsWith(suffix)
                    && token.length() - suffix.length() >= 4) {
                return token.substring(
                        0,
                        token.length() - suffix.length()
                );
            }
        }
        return token;
    }
}
