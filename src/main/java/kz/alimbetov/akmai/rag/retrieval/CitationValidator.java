package kz.alimbetov.akmai.rag.retrieval;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class CitationValidator {

    private static final Pattern SOURCE = Pattern.compile("\\[SOURCE\\s+(\\d+)]");

    public CitationValidation validate(String answer, List<RetrievalHit> context) {
        String input = answer == null ? "" : answer;
        Matcher matcher = SOURCE.matcher(input);
        Set<Integer> cited = new LinkedHashSet<>();
        List<Integer> invalid = new ArrayList<>();
        StringBuffer sanitized = new StringBuffer();

        while (matcher.find()) {
            Integer number = parseBounded(matcher.group(1));
            boolean valid = number != null
                    && number >= 1
                    && number <= context.size();
            if (valid) {
                cited.add(number);
                matcher.appendReplacement(
                        sanitized,
                        Matcher.quoteReplacement(matcher.group())
                );
            } else {
                if (number != null) {
                    invalid.add(number);
                }
                matcher.appendReplacement(sanitized, "");
            }
        }
        matcher.appendTail(sanitized);

        List<SourceRef> sources = cited.stream()
                .map(number -> SourceRef.from(number, context.get(number - 1)))
                .toList();
        return new CitationValidation(
                sanitized.toString(),
                sources,
                List.copyOf(invalid)
        );
    }

    public boolean hasValidCitation(String answer, List<RetrievalHit> context) {
        return !validate(answer, context).citedSources().isEmpty();
    }

    private Integer parseBounded(String digits) {
        try {
            BigInteger value = new BigInteger(digits);
            if (value.signum() < 0
                    || value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                return null;
            }
            return value.intValue();
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public record CitationValidation(
            String answer,
            List<SourceRef> citedSources,
            List<Integer> invalidSourceNumbers
    ) {
    }
}
