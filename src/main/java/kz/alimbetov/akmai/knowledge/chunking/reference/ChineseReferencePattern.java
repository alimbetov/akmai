package kz.alimbetov.akmai.knowledge.chunking.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kz.alimbetov.akmai.knowledge.reference.CrossReferenceType;
import kz.alimbetov.akmai.knowledge.reference.ReferenceTargetScope;

public final class ChineseReferencePattern implements ReferencePattern {

    private static final int MAX_RANGE_SIZE = 100;
    private static final String NUMBER = "[一二三四五六七八九十百千万零〇两0-9]+";

    private static final Pattern RANGE = Pattern.compile(
            "第(" + NUMBER + ")(条|款|项|编|章|节)至第(" + NUMBER + ")\\2"
    );
    private static final Pattern SINGLE = Pattern.compile(
            "第(" + NUMBER + ")(条|款|项|编|章|节)"
    );

    @Override
    public List<RawMatch> find(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<RawMatch> result = new ArrayList<>();
        List<Span> coveredRanges = new ArrayList<>();

        Matcher range = RANGE.matcher(text);
        while (range.find()) {
            CrossReferenceType type = type(range.group(2));
            String from = ReferenceNumberParser.canonicalChinese(range.group(1));
            String to = ReferenceNumberParser.canonicalChinese(range.group(3));
            for (String value : expand(from, to)) {
                result.add(match(range, type, value));
            }
            coveredRanges.add(new Span(range.start(), range.end()));
        }

        Matcher single = SINGLE.matcher(text);
        while (single.find()) {
            if (coveredRanges.stream().anyMatch(span ->
                    single.start() >= span.start()
                            && single.end() <= span.end())) {
                continue;
            }
            result.add(match(
                    single,
                    type(single.group(2)),
                    ReferenceNumberParser.canonicalChinese(single.group(1))
            ));
        }
        return List.copyOf(result);
    }

    private List<String> expand(String from, String to) {
        if (!from.matches("[0-9]+") || !to.matches("[0-9]+")) {
            return List.of(from);
        }
        return ReferenceNumberParser.expandIntegerRange(
                from,
                to,
                MAX_RANGE_SIZE
        );
    }

    private RawMatch match(
            Matcher matcher,
            CrossReferenceType type,
            String canonical
    ) {
        return new RawMatch(
                matcher.start(),
                matcher.end(),
                type,
                canonical,
                matcher.group(),
                "zh",
                ReferenceTargetScope.SAME_DOCUMENT,
                null
        );
    }

    private CrossReferenceType type(String token) {
        return switch (token) {
            case "条" -> CrossReferenceType.ARTICLE;
            case "款" -> CrossReferenceType.PARAGRAPH;
            case "项" -> CrossReferenceType.SUBPARAGRAPH;
            case "编" -> CrossReferenceType.PART;
            case "章" -> CrossReferenceType.CHAPTER;
            default -> CrossReferenceType.SECTION;
        };
    }

    private record Span(int start, int end) {
    }
}
