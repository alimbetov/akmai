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
        List<DetectedIdentifier> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1);
            result.add(new DetectedIdentifier(
                    type(),
                    value,
                    normalizer.normalize(value),
                    context(text, matcher.start(), matcher.end())
            ));
        }
        return result;
    }

    private String context(String text, int start, int end) {
        int from = Math.max(0, start - 80);
        int to = Math.min(text.length(), end + 80);
        return text.substring(from, to).trim();
    }
}
