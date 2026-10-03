package kz.alimbetov.akmai.knowledge.chunking.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.reference.CrossReferenceType;
import kz.alimbetov.akmai.knowledge.reference.ReferenceTargetScope;

public final class WordReferencePattern implements ReferencePattern {

    private static final int MAX_RANGE_SIZE = 100;

    private static final List<Keyword> KEYWORDS = List.of(
            keyword("стат(?:ья|ьи|ье|ью|ьёй|ьей)|ст\\.", "ru", CrossReferenceType.ARTICLE, false),
            keyword("пункт(?:а|е|у|ы|ов)?|п\\.", "ru", CrossReferenceType.PARAGRAPH, false),
            keyword("раздел(?:а|е|у|ы|ов)?", "ru", CrossReferenceType.SECTION, true),
            keyword("глава|главы|главе|главу", "ru", CrossReferenceType.CHAPTER, true),
            keyword("часть|части", "ru", CrossReferenceType.PART, true),
            keyword("бап", "kk", CrossReferenceType.ARTICLE, false),
            keyword("тармақша", "kk", CrossReferenceType.SUBPARAGRAPH, false),
            keyword("тармақ", "kk", CrossReferenceType.PARAGRAPH, false),
            keyword("article(?:s)?", "en", CrossReferenceType.ARTICLE, false),
            keyword("clause(?:s)?", "en", CrossReferenceType.CLAUSE, false),
            keyword("section(?:s)?", "en", CrossReferenceType.SECTION, true),
            keyword("chapter(?:s)?", "en", CrossReferenceType.CHAPTER, true),
            keyword("part(?:s)?", "en", CrossReferenceType.PART, true),
            keyword("artikel", "de", CrossReferenceType.ARTICLE, false),
            keyword("abschnitt", "de", CrossReferenceType.SECTION, true),
            keyword("kapitel", "de", CrossReferenceType.CHAPTER, true),
            keyword("teil", "de", CrossReferenceType.PART, true),
            keyword("paragraphe", "fr", CrossReferenceType.PARAGRAPH, false),
            keyword("chapitre", "fr", CrossReferenceType.CHAPTER, true),
            keyword("partie", "fr", CrossReferenceType.PART, true),
            keyword("artículo(?:s)?", "es", CrossReferenceType.ARTICLE, false),
            keyword("sección|secciones", "es", CrossReferenceType.SECTION, true),
            keyword("capítulo(?:s)?", "es", CrossReferenceType.CHAPTER, true),
            keyword("apartado(?:s)?", "es", CrossReferenceType.PARAGRAPH, false),
            keyword("artigo(?:s)?", "pt", CrossReferenceType.ARTICLE, false),
            keyword("secção|secções|seção|seções", "pt", CrossReferenceType.SECTION, true),
            keyword("capítulo(?:s)?", "pt", CrossReferenceType.CHAPTER, true),
            keyword("articolo|articoli", "it", CrossReferenceType.ARTICLE, false),
            keyword("sezione|sezioni", "it", CrossReferenceType.SECTION, true),
            keyword("capitolo|capitoli", "it", CrossReferenceType.CHAPTER, true),
            keyword("comma|commi", "it", CrossReferenceType.PARAGRAPH, false),
            keyword("madde", "tr", CrossReferenceType.ARTICLE, false),
            keyword("fıkra", "tr", CrossReferenceType.PARAGRAPH, false),
            keyword("bölüm", "tr", CrossReferenceType.SECTION, true),
            keyword("kısım", "tr", CrossReferenceType.PART, true),
            keyword("άρθρο|άρθρα", "el", CrossReferenceType.ARTICLE, false),
            keyword("παράγραφος|παράγραφοι", "el", CrossReferenceType.PARAGRAPH, false),
            keyword("ενότητα|ενότητες", "el", CrossReferenceType.SECTION, true),
            keyword("κεφάλαιο|κεφάλαια", "el", CrossReferenceType.CHAPTER, true),
            keyword("μέρος|μέρη", "el", CrossReferenceType.PART, true),
            keyword("art\\.", "en", CrossReferenceType.ARTICLE, false),
            keyword("sec\\.", "en", CrossReferenceType.SECTION, true)
    );

    private static final Pattern WORD_BEFORE_NUMBER = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(" + keywordExpression() + ")"
                    + "(?![\\p{L}\\p{N}])\\s*"
                    + "([0-9]+(?:\\.[0-9]+)*(?:[-‑][0-9]+|[\\p{L}])?|[IVXLCDM]+)"
                    + "(?:\\s*[–—]\\s*([0-9]+))?"
    );

    private static final Pattern KAZAKH_NUMBER_BEFORE = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])"
                    + "([0-9]+(?:\\.[0-9]+)*(?:[-‑][0-9]+)?)"
                    + "[-‑–—]?"
                    + "(бап|тармақша|тармақ)(?![\\p{L}\\p{N}])"
    );

    @Override
    public List<RawMatch> find(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<RawMatch> result = new ArrayList<>();
        collectWordBefore(text, result);
        collectKazakhNumberBefore(text, result);
        return List.copyOf(result);
    }

    private void collectWordBefore(String text, List<RawMatch> result) {
        Matcher matcher = WORD_BEFORE_NUMBER.matcher(text);
        while (matcher.find()) {
            Keyword keyword = keywordFor(matcher.group(1));
            if (keyword == null) {
                continue;
            }

            String rawNumber = matcher.group(2);
            boolean roman = rawNumber.matches("(?i)[IVXLCDM]+");
            String canonical;
            if (roman) {
                if (!keyword.romanAllowed()) {
                    continue;
                }
                OptionalInt value = ReferenceNumberParser.romanToInt(rawNumber);
                if (value.isEmpty()) {
                    continue;
                }
                canonical = Integer.toString(value.getAsInt());
            } else {
                canonical = ReferenceNumberParser.canonicalArabic(rawNumber);
            }

            String rangeEnd = matcher.group(3);
            if (rangeEnd == null || roman || !canonical.matches("[0-9]+")) {
                result.add(rawMatch(matcher, keyword, canonical));
                continue;
            }

            for (String value : ReferenceNumberParser.expandIntegerRange(
                    canonical,
                    rangeEnd,
                    MAX_RANGE_SIZE
            )) {
                result.add(rawMatch(matcher, keyword, value));
            }
        }
    }

    private void collectKazakhNumberBefore(
            String text,
            List<RawMatch> result
    ) {
        Matcher matcher = KAZAKH_NUMBER_BEFORE.matcher(text);
        while (matcher.find()) {
            String token = matcher.group(2).toLowerCase(Locale.ROOT);
            CrossReferenceType type = switch (token) {
                case "бап" -> CrossReferenceType.ARTICLE;
                case "тармақша" -> CrossReferenceType.SUBPARAGRAPH;
                default -> CrossReferenceType.PARAGRAPH;
            };
            result.add(new RawMatch(
                    matcher.start(),
                    matcher.end(),
                    type,
                    ReferenceNumberParser.canonicalArabic(matcher.group(1)),
                    matcher.group().trim(),
                    "kk",
                    ReferenceTargetScope.SAME_DOCUMENT,
                    null
            ));
        }
    }

    private RawMatch rawMatch(
            Matcher matcher,
            Keyword keyword,
            String canonical
    ) {
        return new RawMatch(
                matcher.start(),
                matcher.end(),
                keyword.type(),
                canonical,
                matcher.group().trim(),
                keyword.language(),
                ReferenceTargetScope.SAME_DOCUMENT,
                null
        );
    }

    private static Keyword keywordFor(String token) {
        for (Keyword keyword : KEYWORDS) {
            if (keyword.pattern().matcher(token).matches()) {
                return keyword;
            }
        }
        return null;
    }

    private static String keywordExpression() {
        return KEYWORDS.stream()
                .map(Keyword::expression)
                .collect(Collectors.joining("|"));
    }

    private static Keyword keyword(
            String expression,
            String language,
            CrossReferenceType type,
            boolean romanAllowed
    ) {
        return new Keyword(
                expression,
                Pattern.compile("(?iu)^(?:" + expression + ")$"),
                language,
                type,
                romanAllowed
        );
    }

    private record Keyword(
            String expression,
            Pattern pattern,
            String language,
            CrossReferenceType type,
            boolean romanAllowed
    ) {
    }
}
