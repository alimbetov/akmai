package kz.alimbetov.akmai.knowledge.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SemanticConceptMatcher {

    private final SemanticConceptSurfaceRegistry surfaces;
    private final SemanticMorphologyRegistry morphologyRegistry;

    public SemanticConceptMatcher(
            SemanticConceptSurfaceRegistry surfaces,
            SemanticMorphologyRegistry morphologyRegistry
    ) {
        this.surfaces = surfaces;
        this.morphologyRegistry = morphologyRegistry;
    }

    public String version(String language) {
        return surfaces.version(language);
    }

    public boolean supports(String language) {
        return surfaces.supports(language)
                && morphologyRegistry.supports(language);
    }

    public List<SemanticConceptMatch> match(
            String text,
            String language
    ) {
        if (!surfaces.supports(language)
                || !morphologyRegistry.supports(language)) {
            return List.of();
        }

        SemanticMorphologyNormalizer morphology =
                morphologyRegistry.require(language);
        List<String> normalizedTokens =
                morphology.normalizeTokens(text);
        if (normalizedTokens.isEmpty()) {
            return List.of();
        }

        String normalizedText = String.join(" ", normalizedTokens);
        String lemmaText = String.join(
                " ",
                morphology.lemmaTokens(text)
        );
        List<String> stemTokens = morphology.stemTokens(text);

        List<SemanticConceptMatch> result = new ArrayList<>();
        for (SemanticConceptSurface surface :
                surfaces.surfaces(language)) {
            SemanticMatchMode mode = strongestMatch(
                    surface,
                    normalizedText,
                    lemmaText,
                    stemTokens
            );
            if (mode == null) {
                continue;
            }

            int tokenCount = surface.preferredPhrase()
                    .split("\\s+")
                    .length;
            result.add(new SemanticConceptMatch(
                    surface.conceptId(),
                    surface.domainId(),
                    surface.subdomainId(),
                    surface.preferredPhrase(),
                    tokenCount * mode.confidence(),
                    mode
            ));
        }

        return List.copyOf(result);
    }

    private SemanticMatchMode strongestMatch(
            SemanticConceptSurface surface,
            String normalizedText,
            String lemmaText,
            List<String> stemTokens
    ) {
        if (containsPhrase(
                normalizedText,
                surface.preferredPhrase()
        )) {
            return SemanticMatchMode.EXACT;
        }

        for (String alias : surface.aliases()) {
            if (containsPhrase(normalizedText, alias)) {
                return SemanticMatchMode.EXACT;
            }
        }

        if (containsPhrase(
                lemmaText,
                surface.lemmaPhrase()
        )) {
            return SemanticMatchMode.LEMMA;
        }

        if (containsTokenSequence(
                stemTokens,
                surface.stemTokens()
        )) {
            return SemanticMatchMode.STEM;
        }
        return null;
    }

    private boolean containsPhrase(
            String text,
            String phrase
    ) {
        if (phrase == null || phrase.isBlank()) {
            return false;
        }
        Pattern pattern = Pattern.compile(
                "(?<![\\p{L}\\p{N}])"
                        + Pattern.quote(phrase)
                        + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE
                        | Pattern.UNICODE_CASE
        );
        return pattern.matcher(text).find();
    }

    private boolean containsTokenSequence(
            List<String> text,
            List<String> phrase
    ) {
        if (phrase.size() < 2 || text.size() < phrase.size()) {
            return false;
        }
        for (int start = 0;
                start <= text.size() - phrase.size();
                start++) {
            boolean equal = true;
            for (int offset = 0;
                    offset < phrase.size();
                    offset++) {
                if (!text.get(start + offset)
                        .equals(phrase.get(offset))) {
                    equal = false;
                    break;
                }
            }
            if (equal) {
                return true;
            }
        }
        return false;
    }
}
