package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SemanticConceptSurfaceRegistryTest {

    private final SemanticDomainCatalog domainCatalog =
            new SemanticDomainCatalog();
    private final EnglishSemanticConceptCatalog conceptCatalog =
            new EnglishSemanticConceptCatalog(domainCatalog);
    private final EnglishSemanticMorphologyNormalizer morphology =
            new EnglishSemanticMorphologyNormalizer();
    private final SemanticConceptSurfaceRegistry registry =
            new SemanticConceptSurfaceRegistry(
                    conceptCatalog,
                    morphology
            );

    @Test
    void everyCanonicalConceptGetsExactlyOneEnglishSurface() {
        assertThat(registry.surfaces("en"))
                .hasSameSizeAs(conceptCatalog.concepts());

        assertThat(registry.surfaces("en"))
                .extracting(SemanticConceptSurface::conceptId)
                .containsExactlyInAnyOrderElementsOf(
                        conceptCatalog.concepts().stream()
                                .map(SemanticConcept::id)
                                .toList()
                );
    }

    @Test
    void everySurfaceKeepsMultiTokenLemmaAndStemSequences() {
        assertThat(registry.surfaces("en"))
                .allSatisfy(surface -> {
                    assertThat(surface.lemmaPhrase())
                            .as(surface.conceptId())
                            .isNotBlank();
                    assertThat(surface.lemmaPhrase().split("\\s+"))
                            .as(surface.conceptId() + "/lemma")
                            .hasSizeGreaterThanOrEqualTo(2);
                    assertThat(surface.stemTokens())
                            .as(surface.conceptId() + "/stems")
                            .hasSizeGreaterThanOrEqualTo(2)
                            .allSatisfy(stem ->
                                    assertThat(stem).isNotBlank()
                            );
                });
    }

    @Test
    void unsupportedLanguagesDoNotFallBackToEnglishSurfaces() {
        assertThat(registry.surfaces("ru")).isEmpty();
        assertThat(registry.surfaces("kk")).isEmpty();
        assertThat(registry.surfaces("zh")).isEmpty();
    }
}
