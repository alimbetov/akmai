package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SemanticConceptSurfaceRegistryTest {

    private final SemanticDomainCatalog domainCatalog =
            new SemanticDomainCatalog();
    private final EnglishSemanticConceptCatalog conceptCatalog =
            new EnglishSemanticConceptCatalog(domainCatalog);
    private final SemanticMorphologyRegistry morphology =
            new SemanticMorphologyRegistry(
                    java.util.List.of(
                            new EnglishSemanticMorphologyNormalizer(),
                            new RussianSemanticMorphologyNormalizer(),
                            new KazakhSemanticMorphologyNormalizer()
                    )
            );
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
    void russianAndKazakhPacksCoverEveryDomainWithCuratedSurfaces() {
        for (String language : java.util.List.of("ru", "kk")) {
            assertThat(registry.surfaces(language))
                    .as(language)
                    .hasSize(32);

            assertThat(registry.surfaces(language))
                    .extracting(SemanticConceptSurface::domainId)
                    .containsExactlyInAnyOrderElementsOf(
                            conceptCatalog.concepts().stream()
                                    .map(SemanticConcept::domainId)
                                    .distinct()
                                    .flatMap(domain ->
                                            java.util.stream.Stream.of(
                                                    domain,
                                                    domain
                                            )
                                    )
                                    .toList()
                    );
        }
    }

    @Test
    void languagesWithoutTranslatedConceptPacksStayUnsupported() {
        assertThat(registry.surfaces("zh")).isEmpty();
        assertThat(registry.surfaces("de")).isEmpty();
        assertThat(registry.version("zh")).isNull();
    }
}
