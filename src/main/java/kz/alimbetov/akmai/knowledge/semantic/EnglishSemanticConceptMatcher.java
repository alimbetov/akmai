package kz.alimbetov.akmai.knowledge.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class EnglishSemanticConceptMatcher {

    private final EnglishSemanticConceptCatalog catalog;

    public EnglishSemanticConceptMatcher(
            EnglishSemanticConceptCatalog catalog
    ) {
        this.catalog = catalog;
    }

    public List<SemanticConceptMatch> match(String text) {
        String normalized =
                EnglishSemanticConceptCatalog.normalizePhrase(text);
        if (normalized.isBlank()) {
            return List.of();
        }

        List<SemanticConceptMatch> result = new ArrayList<>();
        for (SemanticConcept concept : catalog.concepts()) {
            String phrase = concept.preferredPhrase();
            Pattern pattern = Pattern.compile(
                    "(?<![\\p{L}\\p{N}])"
                            + Pattern.quote(phrase)
                            + "(?![\\p{L}\\p{N}])",
                    Pattern.CASE_INSENSITIVE
                            | Pattern.UNICODE_CASE
            );
            if (!pattern.matcher(normalized).find()) {
                continue;
            }

            result.add(new SemanticConceptMatch(
                    concept.id(),
                    concept.domainId(),
                    concept.subdomainId(),
                    phrase,
                    phrase.split("\\s+").length
            ));
        }

        return List.copyOf(result);
    }
}
