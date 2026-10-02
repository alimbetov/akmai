package kz.alimbetov.akmai.knowledge.identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class RegexIdentifierParser implements IdentifierParser {

    private final Pattern pattern;
    private final IdentifierNormalizer normalizer;

    protected RegexIdentifierParser(String regex, IdentifierNormalizer normalizer) {
        this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        this.normalizer = normalizer;
    }

    @Override
    public List<DetectedIdentifier> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<DetectedIdentifier> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = firstCapturedValue(matcher);
            if (value == null) {
                continue;
            }
            value = value.replaceFirst("[,;:!?]+$", "");
            if (value.endsWith(".") && value.indexOf('.') == value.length() - 1) {
                value = value.substring(0, value.length() - 1);
            }
            String normalized = normalizer.normalize(type(), value);
            if (normalized.isBlank()) {
                continue;
            }
            result.add(new DetectedIdentifier(
                    type(),
                    value,
                    normalized,
                    context(text, matcher.start(), matcher.end())
            ));
        }
        return result;
    }

    private String firstCapturedValue(Matcher matcher) {
        for (int group = 1; group <= matcher.groupCount(); group++) {
            String candidate = matcher.group(group);
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private String context(String text, int start, int end) {
        int from = Math.max(0, start - 80);
        int to = Math.min(text.length(), end + 80);
        return text.substring(from, to).trim();
    }
}
