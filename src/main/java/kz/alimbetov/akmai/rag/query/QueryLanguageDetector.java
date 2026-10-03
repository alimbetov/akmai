package kz.alimbetov.akmai.rag.query;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class QueryLanguageDetector {

    private static final Set<Character> KAZAKH_LETTERS =
            Set.of('ә', 'ғ', 'қ', 'ң', 'ө', 'ұ', 'ү', 'һ', 'і');

    private static final Pattern TOKEN =
            Pattern.compile("(?U)[\\p{L}\\p{N}]+");

    private static final List<LanguageProfile> CYRILLIC_PROFILES = List.of(
            new LanguageProfile(
                    "kk",
                    Set.of(
                            "қандай", "қай", "қалай", "қажет", "мерзімі",
                            "доза", "көрсетілім", "қарсы", "бақылау",
                            "міндетті", "құқылы"
                    ),
                    List.of("қандай доза", "қандай мерзім")
            ),
            new LanguageProfile(
                    "ru",
                    Set.of(
                            "какой", "какая", "какие", "как", "срок",
                            "оплаты", "доза", "противопоказания",
                            "мониторинг", "обязан", "вправе", "должен"
                    ),
                    List.of(
                            "какие противопоказания",
                            "какой срок",
                            "какая доза"
                    )
            )
    );

    private static final List<LanguageProfile> LATIN_PROFILES = List.of(
            new LanguageProfile(
                    "en",
                    Set.of(
                            "what", "which", "when", "where", "how",
                            "the", "is", "are", "required", "must", "may",
                            "dose", "dosage", "monitoring", "deadline",
                            "contraindications"
                    ),
                    List.of(
                            "what is",
                            "which dose",
                            "what monitoring",
                            "is required"
                    )
            ),
            new LanguageProfile(
                    "de",
                    Set.of(
                            "welche", "welcher", "welches", "was", "ist",
                            "sind", "muss", "darf", "dosis", "frist",
                            "kontraindikationen", "überwachung", "angegeben"
                    ),
                    List.of(
                            "welche dosis",
                            "welche kontraindikationen",
                            "ist erforderlich"
                    )
            ),
            new LanguageProfile(
                    "fr",
                    Set.of(
                            "quel", "quelle", "quels", "quelles", "est",
                            "sont", "doit", "peut", "dose", "délai",
                            "surveillance", "contre", "indications", "requise"
                    ),
                    List.of(
                            "quelle dose",
                            "quelle surveillance",
                            "contre indications",
                            "est requise"
                    )
            ),
            new LanguageProfile(
                    "es",
                    Set.of(
                            "qué", "cual", "cuál", "cuáles", "es", "son",
                            "debe", "puede", "dosis", "plazo",
                            "contraindicaciones", "seguimiento", "recomienda"
                    ),
                    List.of(
                            "qué dosis",
                            "qué plazo",
                            "qué contraindicaciones"
                    )
            ),
            new LanguageProfile(
                    "pt",
                    Set.of(
                            "qual", "quais", "é", "são", "deve", "pode",
                            "dose", "prazo", "contraindicações",
                            "monitorização", "monitoramento", "necessária"
                    ),
                    List.of(
                            "qual dose",
                            "qual prazo",
                            "qual monitorização"
                    )
            ),
            new LanguageProfile(
                    "it",
                    Set.of(
                            "quale", "quali", "è", "sono", "deve", "può",
                            "dose", "termine", "controindicazioni",
                            "monitoraggio", "richiesta"
                    ),
                    List.of(
                            "quale dose",
                            "quale termine",
                            "quale monitoraggio"
                    )
            ),
            new LanguageProfile(
                    "tr",
                    Set.of(
                            "hangi", "nedir", "ne", "doz", "süre",
                            "kontrendikasyon", "izlem", "gerekir",
                            "gereklidir", "zorundadır", "olabilir"
                    ),
                    List.of(
                            "hangi doz",
                            "hangi süre",
                            "hangi kontrendikasyon"
                    )
            )
    );

    public String detect(String text) {
        return decision(text).primary();
    }

    public LanguageDecision decision(String text) {
        String value = normalized(text);
        if (value.isBlank()) {
            return new LanguageDecision("unknown", List.of(), 0.0);
        }
        if (value.codePoints().anyMatch(this::isHan)) {
            return known("zh", 0.99);
        }
        if (value.codePoints().anyMatch(this::isGreek)) {
            return known("el", 0.99);
        }
        if (value.chars()
                .mapToObj(c -> (char) c)
                .anyMatch(KAZAKH_LETTERS::contains)) {
            return known("kk", 0.98);
        }
        if (value.codePoints().anyMatch(this::isCyrillic)) {
            return scoredDecision(value, CYRILLIC_PROFILES, 0.93);
        }
        if (value.codePoints().anyMatch(this::isLatin)) {
            String distinctive = distinctiveLatin(value);
            if (distinctive != null) {
                return known(distinctive, 0.98);
            }
            return scoredDecision(value, LATIN_PROFILES, 0.94);
        }
        return new LanguageDecision("unknown", List.of(), 0.0);
    }

    private LanguageDecision scoredDecision(
            String value,
            List<LanguageProfile> profiles,
            double knownConfidence
    ) {
        Set<String> tokens = TOKEN.matcher(value)
                .results()
                .map(match -> match.group().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());

        List<ScoredLanguage> scores = profiles.stream()
                .map(profile -> new ScoredLanguage(
                        profile.code(),
                        score(value, tokens, profile)
                ))
                .sorted(Comparator
                        .comparingInt(ScoredLanguage::score)
                        .reversed()
                        .thenComparing(ScoredLanguage::code))
                .toList();

        ScoredLanguage best = scores.getFirst();
        int runnerUp = scores.size() > 1
                ? scores.get(1).score()
                : 0;
        if (best.score() >= 3 && best.score() > runnerUp) {
            return known(best.code(), knownConfidence);
        }
        if (best.score() >= 2 && runnerUp == 0) {
            return known(best.code(), knownConfidence - 0.06);
        }

        List<String> candidates = new ArrayList<>();
        scores.stream()
                .filter(score -> score.score() > 0)
                .limit(3)
                .map(ScoredLanguage::code)
                .forEach(candidates::add);
        return new LanguageDecision(
                "unknown",
                List.copyOf(candidates),
                candidates.isEmpty() ? 0.0 : 0.55
        );
    }

    private int score(
            String value,
            Set<String> tokens,
            LanguageProfile profile
    ) {
        int score = 0;
        for (String marker : profile.markers()) {
            if (tokens.contains(marker)) {
                score++;
            }
        }
        for (String phrase : profile.phrases()) {
            if (value.contains(phrase)) {
                score += 2;
            }
        }
        return score;
    }

    private String distinctiveLatin(String value) {
        if (containsAny(value, "ß", "ä")) {
            return "de";
        }
        if (containsAny(value, "ñ", "¿", "¡")) {
            return "es";
        }
        if (containsAny(value, "ã", "õ")) {
            return "pt";
        }
        if (containsAny(value, "ğ", "ı", "ş")) {
            return "tr";
        }
        if (containsAny(value, "œ", "æ")) {
            return "fr";
        }
        return null;
    }

    private boolean containsAny(String value, String... markers) {
        for (String marker : markers) {
            if (value.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private LanguageDecision known(String code, double confidence) {
        return new LanguageDecision(code, List.of(code), confidence);
    }

    private String normalized(String text) {
        return Normalizer.normalize(
                text == null ? "" : text,
                Normalizer.Form.NFC
        ).toLowerCase(Locale.ROOT);
    }

    private boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.HAN;
    }

    private boolean isCyrillic(int codePoint) {
        return Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.CYRILLIC;
    }

    private boolean isGreek(int codePoint) {
        return Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.GREEK;
    }

    private boolean isLatin(int codePoint) {
        return Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.LATIN;
    }

    private record LanguageProfile(
            String code,
            Set<String> markers,
            List<String> phrases
    ) {
    }

    private record ScoredLanguage(
            String code,
            int score
    ) {
    }
}
