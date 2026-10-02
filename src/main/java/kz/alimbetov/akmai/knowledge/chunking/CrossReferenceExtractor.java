package kz.alimbetov.akmai.knowledge.chunking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kz.alimbetov.akmai.knowledge.reference.CrossReference;
import kz.alimbetov.akmai.knowledge.reference.CrossReferenceType;
import kz.alimbetov.akmai.knowledge.reference.ReferenceTargetScope;
import kz.alimbetov.akmai.knowledge.reference.StructuralAnchor;
import org.springframework.stereotype.Component;

@Component
public class CrossReferenceExtractor {

    private static final Pattern WORD_BEFORE_NUMBER = Pattern.compile(
            "(?iu)\\b(стат(?:ья|ьи|ье|ью|ьёй|ьей)|ст\\.|пункт(?:а|е|у)?|п\\.|article|art\\.|section|clause|бап|тармақ|тармақша)\\s*([0-9]+(?:\\.[0-9]+)*)"
    );
    private static final Pattern KAZAKH_NUMBER_BEFORE = Pattern.compile(
            "(?iu)\\b([0-9]+(?:\\.[0-9]+)*)[-‑–—]?(бап|тармақ|тармақша)\\b"
    );
    private static final Pattern CHINESE = Pattern.compile(
            "第([一二三四五六七八九十百千万零〇两0-9]+)(条|款|项)"
    );

    public List<String> extract(String text) {
        return extractTyped(text).stream()
                .map(CrossReference::rawValue)
                .distinct()
                .toList();
    }

    public List<CrossReference> extractTyped(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        StructuralAnchor declaration = extractAnchor(text).orElse(null);
        LinkedHashMap<String, CrossReference> result = new LinkedHashMap<>();

        collectWordBefore(text, declaration, result);
        collectKazakh(text, declaration, result);
        collectChinese(text, declaration, result);

        return List.copyOf(result.values());
    }

    public Optional<StructuralAnchor> extractAnchor(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String firstLine = text.stripLeading().lines().findFirst().orElse("");

        Matcher word = WORD_BEFORE_NUMBER.matcher(firstLine);
        if (word.find() && word.start() == 0) {
            CrossReferenceType type = wordType(word.group(1));
            return Optional.of(new StructuralAnchor(
                    type,
                    canonicalNumber(word.group(2)),
                    word.group().trim(),
                    language(word.group(1))
            ));
        }

        Matcher kazakh = KAZAKH_NUMBER_BEFORE.matcher(firstLine);
        if (kazakh.find() && kazakh.start() == 0) {
            return Optional.of(new StructuralAnchor(
                    kazakhType(kazakh.group(2)),
                    canonicalNumber(kazakh.group(1)),
                    kazakh.group().trim(),
                    "kk"
            ));
        }

        Matcher chinese = CHINESE.matcher(firstLine);
        if (chinese.find() && chinese.start() == 0) {
            return Optional.of(new StructuralAnchor(
                    chineseType(chinese.group(2)),
                    canonicalChineseNumber(chinese.group(1)),
                    chinese.group().trim(),
                    "zh"
            ));
        }
        return Optional.empty();
    }

    private void collectWordBefore(
            String text,
            StructuralAnchor declaration,
            LinkedHashMap<String, CrossReference> result
    ) {
        Matcher matcher = WORD_BEFORE_NUMBER.matcher(text);
        while (matcher.find()) {
            CrossReferenceType type = wordType(matcher.group(1));
            String canonical = canonicalNumber(matcher.group(2));
            if (isDeclarationOccurrence(matcher.start(), declaration, type, canonical)) {
                continue;
            }
            add(result, new CrossReference(
                    type,
                    canonical,
                    matcher.group().trim(),
                    language(matcher.group(1)),
                    ReferenceTargetScope.SAME_DOCUMENT,
                    null
            ));
        }
    }

    private void collectKazakh(
            String text,
            StructuralAnchor declaration,
            LinkedHashMap<String, CrossReference> result
    ) {
        Matcher matcher = KAZAKH_NUMBER_BEFORE.matcher(text);
        while (matcher.find()) {
            CrossReferenceType type = kazakhType(matcher.group(2));
            String canonical = canonicalNumber(matcher.group(1));
            if (isDeclarationOccurrence(matcher.start(), declaration, type, canonical)) {
                continue;
            }
            add(result, new CrossReference(
                    type,
                    canonical,
                    matcher.group().trim(),
                    "kk",
                    ReferenceTargetScope.SAME_DOCUMENT,
                    null
            ));
        }
    }

    private void collectChinese(
            String text,
            StructuralAnchor declaration,
            LinkedHashMap<String, CrossReference> result
    ) {
        Matcher matcher = CHINESE.matcher(text);
        while (matcher.find()) {
            CrossReferenceType type = chineseType(matcher.group(2));
            String canonical = canonicalChineseNumber(matcher.group(1));
            if (isDeclarationOccurrence(matcher.start(), declaration, type, canonical)) {
                continue;
            }
            add(result, new CrossReference(
                    type,
                    canonical,
                    matcher.group().trim(),
                    "zh",
                    ReferenceTargetScope.SAME_DOCUMENT,
                    null
            ));
        }
    }

    private boolean isDeclarationOccurrence(
            int start,
            StructuralAnchor declaration,
            CrossReferenceType type,
            String canonical
    ) {
        return start == 0
                && declaration != null
                && declaration.type() == type
                && declaration.canonicalValue().equals(canonical);
    }

    private void add(
            LinkedHashMap<String, CrossReference> result,
            CrossReference value
    ) {
        result.putIfAbsent(
                value.type() + "|" + value.canonicalValue()
                        + "|" + value.targetScope()
                        + "|" + value.targetDocumentId(),
                value
        );
    }

    private CrossReferenceType wordType(String token) {
        String value = token.toLowerCase(Locale.ROOT);
        if (value.startsWith("стат")
                || value.startsWith("ст.")
                || value.startsWith("article")
                || value.startsWith("art.")
                || value.equals("бап")) {
            return CrossReferenceType.ARTICLE;
        }
        if (value.startsWith("section")) {
            return CrossReferenceType.SECTION;
        }
        if (value.startsWith("clause")) {
            return CrossReferenceType.CLAUSE;
        }
        if (value.contains("тармақша")) {
            return CrossReferenceType.SUBPARAGRAPH;
        }
        return CrossReferenceType.PARAGRAPH;
    }

    private CrossReferenceType kazakhType(String token) {
        String value = token.toLowerCase(Locale.ROOT);
        if ("бап".equals(value)) {
            return CrossReferenceType.ARTICLE;
        }
        if ("тармақша".equals(value)) {
            return CrossReferenceType.SUBPARAGRAPH;
        }
        return CrossReferenceType.PARAGRAPH;
    }

    private CrossReferenceType chineseType(String token) {
        return switch (token) {
            case "条" -> CrossReferenceType.ARTICLE;
            case "款" -> CrossReferenceType.PARAGRAPH;
            default -> CrossReferenceType.SUBPARAGRAPH;
        };
    }

    private String language(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.matches(".*[а-яәіңғүұқөһ].*")) {
            return lower.contains("бап") || lower.contains("тармақ")
                    ? "kk"
                    : "ru";
        }
        return "en";
    }

    private String canonicalNumber(String raw) {
        return raw.replaceFirst("^0+(?!$)", "");
    }

    private String canonicalChineseNumber(String raw) {
        if (raw.chars().allMatch(Character::isDigit)) {
            return canonicalNumber(raw);
        }
        int total = 0;
        int current = 0;
        for (int codePoint : raw.codePoints().toArray()) {
            int digit = chineseDigit(codePoint);
            if (digit >= 0) {
                current = digit;
                continue;
            }
            int unit = chineseUnit(codePoint);
            if (unit > 0) {
                total += (current == 0 ? 1 : current) * unit;
                current = 0;
            }
        }
        total += current;
        return total > 0 ? Integer.toString(total) : raw;
    }

    private int chineseDigit(int codePoint) {
        return switch (codePoint) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    private int chineseUnit(int codePoint) {
        return switch (codePoint) {
            case '十' -> 10;
            case '百' -> 100;
            case '千' -> 1000;
            case '万' -> 10000;
            default -> 0;
        };
    }
}
