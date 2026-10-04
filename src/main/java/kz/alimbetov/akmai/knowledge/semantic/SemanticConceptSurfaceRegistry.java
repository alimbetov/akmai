package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SemanticConceptSurfaceRegistry {

    private final EnglishSemanticConceptCatalog catalog;
    private final EnglishSemanticMorphologyNormalizer normalizer;
    private final List<SemanticConceptSurface> englishSurfaces;

    public SemanticConceptSurfaceRegistry(
            EnglishSemanticConceptCatalog catalog,
            EnglishSemanticMorphologyNormalizer normalizer
    ) {
        this.catalog = catalog;
        this.normalizer = normalizer;
        this.englishSurfaces = catalog.concepts().stream()
                .map(this::englishSurface)
                .toList();
    }

    public String version() {
        return catalog.version();
    }

    public List<SemanticConceptSurface> surfaces(String language) {
        if ("en".equals(language)) {
            return englishSurfaces;
        }
        return List.of();
    }

    private SemanticConceptSurface englishSurface(
            SemanticConcept concept
    ) {
        return new SemanticConceptSurface(
                concept.id(),
                "en",
                concept.preferredPhrase(),
                normalizer.lemmaPhrase(concept.preferredPhrase()),
                List.of(),
                normalizer.stemTokens(concept.preferredPhrase())
        );
    }
}
