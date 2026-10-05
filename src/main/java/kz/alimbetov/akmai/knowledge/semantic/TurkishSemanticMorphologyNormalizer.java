package kz.alimbetov.akmai.knowledge.semantic;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class TurkishSemanticMorphologyNormalizer
        implements SemanticMorphologyNormalizer {

    private static final Locale TURKISH = Locale.forLanguageTag("tr");

    private static final List<String> INFLECTIONAL_SUFFIXES =
            List.of(
                            "larınız", "leriniz", "larımız", "lerimiz",
                            "larının", "lerinin", "lardan", "lerden",
                            "larda", "lerde", "ları", "leri",
                            "ımız", "imiz", "umuz", "ümüz",
                            "ınız", "iniz", "unuz", "ünüz",
                            "nın", "nin", "nun", "nün",
                            "dan", "den", "tan", "ten",
                            "lar", "ler",
                            "yla", "yle",
                            "ya", "ye",
                            "da", "de", "ta", "te",
                            "ı", "i", "u", "ü",
                            "a", "e"
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
                            "laştırma", "leştirme",
                            "laştır", "leştir",
                            "laşma", "leşme",
                            "lık", "lik", "luk", "lük",
                            "sal", "sel",
                            "cı", "ci", "cu", "cü",
                            "çı", "çi", "çu", "çü"
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
        return "tr";
    }

    @Override
    public List<String> normalizeTokens(String text) {
        String normalized = Normalizer.normalize(
                        text == null ? "" : text,
                        Normalizer.Form.NFC
                )
                .toLowerCase(TURKISH)
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
                .map(this::stripInflectionLayers)
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

    private String stripInflectionLayers(String token) {
        String current = token;
        for (int layer = 0; layer < 3; layer++) {
            String stripped = stripOnce(
                    current,
                    INFLECTIONAL_SUFFIXES,
                    3
            );
            if (stripped.equals(current)) {
                break;
            }
            current = stripped;
        }
        return current;
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
