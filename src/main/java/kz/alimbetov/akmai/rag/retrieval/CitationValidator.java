package kz.alimbetov.akmai.rag.retrieval;

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
        Matcher matcher = SOURCE.matcher(answer == null ? "" : answer);
        Set<Integer> cited = new LinkedHashSet<>();
        Set<Integer> invalid = new LinkedHashSet<>();
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            if (number >= 1 && number <= context.size()) {
                cited.add(number);
            } else {
                invalid.add(number);
            }
        }

        String sanitized = answer == null ? "" : answer;
        for (Integer number : invalid) {
            sanitized = sanitized.replace("[SOURCE " + number + "]", "");
        }

        List<SourceRef> sources = cited.stream()
                .map(number -> SourceRef.from(number, context.get(number - 1)))
                .toList();
        return new CitationValidation(sanitized, sources, List.copyOf(invalid));
    }

    public record CitationValidation(
            String answer,
            List<SourceRef> citedSources,
            List<Integer> invalidSourceNumbers
    ) {
    }
}
