package kz.alimbetov.akmai.knowledge.chunking.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kz.alimbetov.akmai.knowledge.reference.CrossReferenceType;
import kz.alimbetov.akmai.knowledge.reference.ReferenceTargetScope;

public final class ExternalReferencePattern implements ReferencePattern {

    private static final Pattern KZ_LAW = Pattern.compile(
            "(?iu)(Закон(?:а|ом|е)?\\s+(?:РК|Республики Казахстан)"
                    + "\\s+от\\s+(\\d{1,2}\\.\\d{1,2}\\.\\d{4})"
                    + "\\s+№\\s*([\\p{L}\\p{N}-]+))"
    );
    private static final Pattern STANDARD = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])"
                    + "(ISO|IEC|ГОСТ(?:\\s+Р)?|СТ\\s*РК)\\s+"
                    + "([0-9]+(?:[-:][0-9A-ZА-Я]+)*)"
                    + "(?![\\p{L}\\p{N}])"
    );
    private static final Pattern RFC = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])RFC\\s+([0-9]{1,6})(?![\\p{L}\\p{N}])"
    );

    @Override
    public List<RawMatch> find(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<RawMatch> result = new ArrayList<>();
        collectLaw(text, result);
        collectStandards(text, result);
        collectRfc(text, result);
        return List.copyOf(result);
    }

    private void collectLaw(String text, List<RawMatch> result) {
        Matcher matcher = KZ_LAW.matcher(text);
        while (matcher.find()) {
            String target = "KZ-LAW " + matcher.group(2)
                    + " №" + matcher.group(3);
            result.add(new RawMatch(
                    matcher.start(),
                    matcher.end(),
                    CrossReferenceType.EXTERNAL_LAW,
                    target,
                    matcher.group(1).trim(),
                    "ru",
                    ReferenceTargetScope.EXPLICIT_DOCUMENT,
                    target
            ));
        }
    }

    private void collectStandards(String text, List<RawMatch> result) {
        Matcher matcher = STANDARD.matcher(text);
        while (matcher.find()) {
            String prefix = matcher.group(1)
                    .toUpperCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");
            String target = prefix + " "
                    + matcher.group(2).toUpperCase(Locale.ROOT);
            result.add(new RawMatch(
                    matcher.start(),
                    matcher.end(),
                    CrossReferenceType.EXTERNAL_STANDARD,
                    target,
                    matcher.group().trim(),
                    language(prefix),
                    ReferenceTargetScope.EXPLICIT_DOCUMENT,
                    target
            ));
        }
    }

    private void collectRfc(String text, List<RawMatch> result) {
        Matcher matcher = RFC.matcher(text);
        while (matcher.find()) {
            String target = "RFC " + matcher.group(1);
            result.add(new RawMatch(
                    matcher.start(),
                    matcher.end(),
                    CrossReferenceType.EXTERNAL_TECHNICAL,
                    target,
                    matcher.group().trim(),
                    "en",
                    ReferenceTargetScope.EXPLICIT_DOCUMENT,
                    target
            ));
        }
    }

    private String language(String prefix) {
        return prefix.startsWith("ГОСТ") || prefix.startsWith("СТ РК")
                ? "ru"
                : "en";
    }
}
