package kz.alimbetov.akmai.knowledge.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class EnglishSemanticConceptMatcher {

    private final SemanticConceptSurfaceRegistry surfaces;
    private final EnglishSemanticMorphologyNormalizer morphology;

    public EnglishSemanticConceptMatcher(
            EnglishSemanticConceptCatalog catalog
    ) {
        this(
                new SemanticConceptSurfaceRegistry(
                        catalog,
                        new EnglishSemanticMorphologyNormalizer()
                ),
                new EnglishSemanticMorphologyNormalizer()
        );
    }

    @Autowired
    public EnglishSemanticConceptMatcher(
            SemanticConceptSurfaceRegistry surfaces,
            EnglishSemanticMorphologyNormalizer morphology
    ) {
        this.surfaces = surfaces;
        this.morphology = morphology;
    }

    public String version() {
        return surfaces.version();
    }

    public List<SemanticConceptMatch> match(String text) {
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
        for (SemanticConceptSurface surface : surfaces.surfaces("en")) {
            Match match = strongestMatch(
                    surface,
                    normalizedText,
                    lemmaText,
                    stemTokens
            );
            if (match == null) {
                continue;
            }

            int tokenCount = surface.preferredPhrase()
                    .split("\\s+")
                    .length;
            result.add(new SemanticConceptMatch(
                    surface.conceptId(),
                    conceptDomain(surface.conceptId()),
                    conceptSubdomain(surface.conceptId()),
                    surface.preferredPhrase(),
                    tokenCount * match.mode().confidence(),
                    match.mode()
            ));
        }

        return List.copyOf(result);
    }

    private Match strongestMatch(
            SemanticConceptSurface surface,
            String normalizedText,
            String lemmaText,
            List<String> stemTokens
    ) {
        if (containsPhrase(
                normalizedText,
                surface.preferredPhrase()
        )) {
            return new Match(SemanticMatchMode.EXACT);
        }

        for (String alias : surface.aliases()) {
            if (containsPhrase(
                    normalizedText,
                    EnglishSemanticConceptCatalog.normalizePhrase(alias)
            )) {
                return new Match(SemanticMatchMode.EXACT);
            }
        }

        if (containsPhrase(
                lemmaText,
                surface.lemmaPhrase()
        )) {
            return new Match(SemanticMatchMode.LEMMA);
        }

        if (containsTokenSequence(
                stemTokens,
                surface.stemTokens()
        )) {
            return new Match(SemanticMatchMode.STEM);
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

    private String conceptDomain(String conceptId) {
        int separator = conceptId.indexOf('.');
        return separator < 0
                ? conceptId
                : conceptId.substring(0, separator);
    }

    private String conceptSubdomain(String conceptId) {
        int first = conceptId.indexOf('.');
        int second = conceptId.indexOf('.', first + 1);
        if (first < 0 || second < 0) {
            return "";
        }
        return conceptId.substring(first + 1, second);
    }

    private record Match(SemanticMatchMode mode) {
    }
}
