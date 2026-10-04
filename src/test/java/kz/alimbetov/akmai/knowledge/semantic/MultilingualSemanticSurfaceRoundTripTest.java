package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MultilingualSemanticSurfaceRoundTripTest {

    private static final List<String> LANGUAGES = List.of(
            "en", "ru", "kk", "zh", "de", "fr",
            "es", "pt", "it", "tr", "el"
    );

    @Test
    void everyPreferredSurfaceRoundTripsToItsCanonicalConcept() {
        SemanticDomainCatalog domainCatalog =
                new SemanticDomainCatalog();
        EnglishSemanticConceptCatalog conceptCatalog =
                new EnglishSemanticConceptCatalog(domainCatalog);
        SemanticMorphologyRegistry morphology =
                SemanticTestMorphology.registry();
        SemanticConceptSurfaceRegistry surfaces =
                new SemanticConceptSurfaceRegistry(
                        conceptCatalog,
                        morphology
                );
        SemanticConceptMatcher matcher =
                new SemanticConceptMatcher(
                        surfaces,
                        morphology
                );

        for (String language : LANGUAGES) {
            for (SemanticConceptSurface surface :
                    surfaces.surfaces(language)) {
                assertThat(matcher.match(
                        surface.preferredPhrase(),
                        language
                ))
                        .as(language + "/" + surface.conceptId())
                        .extracting(SemanticConceptMatch::conceptId)
                        .contains(surface.conceptId());
            }
        }
    }
}
