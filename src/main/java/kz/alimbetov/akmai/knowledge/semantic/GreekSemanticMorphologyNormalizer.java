package kz.alimbetov.akmai.knowledge.semantic;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class GreekSemanticMorphologyNormalizer
        implements SemanticMorphologyNormalizer {

    private static final Locale GREEK = Locale.forLanguageTag("el");

    private static final List<String> INFLECTIONAL_SUFFIXES =
            List.of(
                            "ων", "ους", "ους", "εις", "εων", "ιας",
                            "ιων", "ικων", "ικες", "ικα",
                            "ος", "ου", "οι", "ες", "ας", "ης",
                            "η", "α", "ο", "ι"
                    )
                    .stream()
                    .distinct()
                    .sorted(
                            Comparator.comparingInt(String::length)
                                    .reversed()
                    )
                    .toList();

    private static final List<String> DERIVATIONAL_SUFFIXES =
            List.of(
                            "ικοτητα", "οτητα", "ισμος", "ιστικος",
                            "ικος", "ικη", "ικο", "τικος",
                            "τικη", "τικο"
                    )
                    .stream()
                    .distinct()
                    .sorted(
                            Comparator.comparingInt(String::length)
                                    .reversed()
                    )
                    .toList();

    @Override
    public String language() {
        return "el";
    }

    @Override
    public List<String> normalizeTokens(String text) {
        String normalized = Normalizer.normalize(
                        text == null ? "" : text,
                        Normalizer.Form.NFD
                )
                .replaceAll("\\p{M}+", "")
                .toLowerCase(GREEK)
                .replace('ς', 'σ')
                .trim()
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return List.of();
        }
        return List.of(normalized.split("\\s+"));
    }

    @Override
    public List<String> lemmaTokens(String text) {
        return normalizeTokens(text).stream()
                .map(token -> stripOnce(
                        token,
                        INFLECTIONAL_SUFFIXES,
                        4
                ))
                .toList();
    }

    @Override
    public List<String> stemTokens(String text) {
        return lemmaTokens(text).stream()
                .map(token -> stripOnce(
                        token,
                        DERIVATIONAL_SUFFIXES,
                        4
                ))
                .toList();
    }

    private String stripOnce(
            String token,
            List<String> suffixes,
            int minimumRemaining
    ) {
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
}
