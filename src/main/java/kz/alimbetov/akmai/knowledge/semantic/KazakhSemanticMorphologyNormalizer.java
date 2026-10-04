package kz.alimbetov.akmai.knowledge.semantic;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class KazakhSemanticMorphologyNormalizer
        implements SemanticMorphologyNormalizer {

    private static final List<String> SUFFIXES =
            List.of(
                            "дарыңыз", "деріңіз", "тарыңыз", "теріңіз",
                            "лардың", "лердің", "дардың", "дердің",
                            "тардың", "тердің", "дағы", "дегі", "тағы",
                            "тегі", "лар", "лер", "дар", "дер", "тар",
                            "тер", "ның", "нің", "дың", "дің", "тың",
                            "тің", "ға", "ге", "қа", "ке", "ды", "ді",
                            "ты", "ті", "дан", "ден", "тан", "тен",
                            "мен", "бен", "пен", "ым", "ім", "ың",
                            "ің", "сы", "сі"
                    )
                    .stream()
                    .sorted(
                            Comparator.comparingInt(String::length)
                                    .reversed()
                    )
                    .toList();

    private static final List<String> DERIVATIONAL_SUFFIXES =
            List.of(
                            "лық", "лік", "дық", "дік", "тық", "тік",
                            "шылық", "шілік", "ландыру", "лендіру",
                            "дыру", "діру", "тыру", "тіру"
                    )
                    .stream()
                    .sorted(
                            Comparator.comparingInt(String::length)
                                    .reversed()
                    )
                    .toList();

    @Override
    public String language() {
        return "kk";
    }

    @Override
    public List<String> normalizeTokens(String text) {
        return SlavicTurkicTextNormalizer.tokens(text);
    }

    @Override
    public List<String> lemmaTokens(String text) {
        return normalizeTokens(text).stream()
                .map(this::stripSuffix)
                .toList();
    }

    @Override
    public List<String> stemTokens(String text) {
        return lemmaTokens(text).stream()
                .map(this::stripDerivation)
                .toList();
    }

    private String stripSuffix(String token) {
        if (token.length() <= 4) {
            return token;
        }
        for (String suffix : SUFFIXES) {
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
