package kz.alimbetov.akmai.knowledge.chunking.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.reference.CrossReferenceType;
import kz.alimbetov.akmai.knowledge.reference.ReferenceTargetScope;

public final class NamedObjectReferencePattern implements ReferencePattern {

    private static final List<Keyword> KEYWORDS = List.of(
            keyword("приложение", "ru", CrossReferenceType.APPENDIX),
            keyword("қосымша", "kk", CrossReferenceType.APPENDIX),
            keyword("appendix", "en", CrossReferenceType.APPENDIX),
            keyword("anhang", "de", CrossReferenceType.APPENDIX),
            keyword("annexe", "fr", CrossReferenceType.APPENDIX),
            keyword("anexo", "es", CrossReferenceType.APPENDIX),
            keyword("allegato", "it", CrossReferenceType.APPENDIX),
            keyword("ek", "tr", CrossReferenceType.APPENDIX),
            keyword("παράρτημα", "el", CrossReferenceType.APPENDIX),
            keyword("таблица", "ru", CrossReferenceType.TABLE),
            keyword("кесте", "kk", CrossReferenceType.TABLE),
            keyword("table", "en", CrossReferenceType.TABLE),
            keyword("tabelle", "de", CrossReferenceType.TABLE),
            keyword("tableau", "fr", CrossReferenceType.TABLE),
            keyword("tabla", "es", CrossReferenceType.TABLE),
            keyword("tabela", "pt", CrossReferenceType.TABLE),
            keyword("tabella", "it", CrossReferenceType.TABLE),
            keyword("tablo", "tr", CrossReferenceType.TABLE),
            keyword("πίνακας", "el", CrossReferenceType.TABLE),
            keyword("рис(?:унок|\\.)", "ru", CrossReferenceType.FIGURE),
            keyword("сурет", "kk", CrossReferenceType.FIGURE),
            keyword("figure|fig\\.", "en", CrossReferenceType.FIGURE),
            keyword("abbildung|abb\\.", "de", CrossReferenceType.FIGURE),
            keyword("figura", "es", CrossReferenceType.FIGURE),
            keyword("figura", "pt", CrossReferenceType.FIGURE),
            keyword("figura", "it", CrossReferenceType.FIGURE),
            keyword("şekil", "tr", CrossReferenceType.FIGURE),
            keyword("σχήμα", "el", CrossReferenceType.FIGURE)
    );

    private static final Pattern PATTERN = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(" + expression() + ")"
                    + "(?![\\p{L}\\p{N}])\\s*№?\\s*([0-9]+|[A-ZА-Я])"
    );

    private static final Pattern CHINESE = Pattern.compile(
            "(附录|附件|表|图)\\s*([0-9]+|[A-Z])"
    );

    @Override
    public List<RawMatch> find(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<RawMatch> result = new ArrayList<>();
        Matcher matcher = PATTERN.matcher(text);
        while (matcher.find()) {
            Keyword keyword = keywordFor(matcher.group(1));
            if (keyword == null) {
                continue;
            }
            result.add(new RawMatch(
                    matcher.start(),
                    matcher.end(),
                    keyword.type(),
                    canonical(matcher.group(2)),
                    matcher.group().trim(),
                    keyword.language(),
                    ReferenceTargetScope.SAME_DOCUMENT,
                    null
            ));
        }

        Matcher chinese = CHINESE.matcher(text);
        while (chinese.find()) {
            CrossReferenceType type = switch (chinese.group(1)) {
                case "附录", "附件" -> CrossReferenceType.APPENDIX;
                case "表" -> CrossReferenceType.TABLE;
                default -> CrossReferenceType.FIGURE;
            };
            result.add(new RawMatch(
                    chinese.start(),
                    chinese.end(),
                    type,
                    canonical(chinese.group(2)),
                    chinese.group().trim(),
                    "zh",
                    ReferenceTargetScope.SAME_DOCUMENT,
                    null
            ));
        }
        return List.copyOf(result);
    }

    private String canonical(String raw) {
        return raw.chars().allMatch(Character::isDigit)
                ? ReferenceNumberParser.canonicalArabic(raw)
                : raw.toUpperCase(Locale.ROOT);
    }

    private static Keyword keywordFor(String token) {
        for (Keyword keyword : KEYWORDS) {
            if (keyword.pattern().matcher(token).matches()) {
                return keyword;
            }
        }
        return null;
    }

    private static String expression() {
        return KEYWORDS.stream()
                .map(Keyword::expression)
                .collect(Collectors.joining("|"));
    }

    private static Keyword keyword(
            String expression,
            String language,
            CrossReferenceType type
    ) {
        return new Keyword(
                expression,
                Pattern.compile("(?iu)^(?:" + expression + ")$"),
                language,
                type
        );
    }

    private record Keyword(
            String expression,
            Pattern pattern,
            String language,
            CrossReferenceType type
    ) {
    }
}
