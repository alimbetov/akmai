package kz.alimbetov.akmai.knowledge.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SemanticDomainRouter {

    private final SemanticDomainCatalog catalog;
    private final Map<String, Pattern> boundedPatterns =
            new ConcurrentHashMap<>();

    public SemanticDomainRouter(SemanticDomainCatalog catalog) {
        this.catalog = catalog;
    }

    public SemanticQueryProfile analyze(
            String text,
            String language
    ) {
        String normalized = SemanticLexiconRegistry.normalize(text);
        if (normalized.isBlank()
                || language == null
                || !catalog.languages().contains(language)) {
            return new SemanticQueryProfile(
                    catalog.version(),
                    language,
                    List.of()
            );
        }

        List<SemanticDomainScore> scores = new ArrayList<>();
        for (SemanticDomainDefinition domain : catalog.domains()) {
            LinkedHashSet<String> matched = new LinkedHashSet<>();
            List<String> anchors = domain.anchors()
                    .getOrDefault(language, List.of());
            for (String anchor : anchors) {
                String candidate = SemanticLexiconRegistry.normalize(anchor);
                if (contains(normalized, candidate, language)) {
                    matched.add(candidate);
                }
            }

            String localizedName = SemanticLexiconRegistry.normalize(
                    domain.names().get(language)
            );
            boolean nameMatch = contains(
                    normalized,
                    localizedName,
                    language
            );

            if (matched.isEmpty() && !nameMatch) {
                continue;
            }

            double score = matched.stream()
                    .mapToDouble(value ->
                            1.0 + Math.min(1.0, value.length() / 32.0)
                    )
                    .sum();
            if (nameMatch) {
                score += 1.5;
            }

            scores.add(new SemanticDomainScore(
                    domain.id(),
                    domain.kind(),
                    score,
                    List.copyOf(matched)
            ));
        }

        scores.sort(
                Comparator.comparingDouble(SemanticDomainScore::score)
                        .reversed()
                        .thenComparing(SemanticDomainScore::domainId)
        );
        return new SemanticQueryProfile(
                catalog.version(),
                language,
                List.copyOf(scores)
        );
    }

    private boolean contains(
            String text,
            String candidate,
            String language
    ) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if ("zh".equals(language)) {
            return text.contains(candidate);
        }
        Pattern bounded = boundedPatterns.computeIfAbsent(
                candidate,
                value -> Pattern.compile(
                        "(?iu)(?<![\\p{L}\\p{N}])"
                                + Pattern.quote(value)
                                + "(?![\\p{L}\\p{N}])"
                )
        );
        return bounded.matcher(text).find();
    }
}
