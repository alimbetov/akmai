package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SemanticConceptSurfaceRegistryTest {

    private static final int MULTILINGUAL_V1_BASELINE = 384;

    private final SemanticDomainCatalog domainCatalog =
            new SemanticDomainCatalog();
    private final EnglishSemanticConceptCatalog conceptCatalog =
            new EnglishSemanticConceptCatalog(domainCatalog);
    private final SemanticMorphologyRegistry morphology =
            SemanticTestMorphology.registry();
    private final SemanticConceptSurfaceRegistry registry =
            new SemanticConceptSurfaceRegistry(
                    conceptCatalog,
                    morphology
            );

    @Test
    void englishSurfacesTrackCurrentCanonicalCorpusWhileTranslatedPacksStayOnTheirVersionedBaseline() {
        assertThat(registry.surfaces("en"))
                .hasSameSizeAs(conceptCatalog.concepts());

        Set<String> canonicalIds = conceptCatalog.concepts().stream()
                .map(SemanticConcept::id)
                .collect(Collectors.toSet());

        for (String language : List.of("ru", "kk", "zh", "de", "fr", "es", "pt", "it", "tr", "el")) {
            assertThat(registry.surfaces(language))
                    .as(language)
                    .hasSize(MULTILINGUAL_V1_BASELINE)
                    .allSatisfy(surface ->
                            assertThat(canonicalIds)
                                    .contains(surface.conceptId())
                    );
        }
    }

    @Test
    void completedV1SurfacePacksPreserveAllDomainsAndSubdomains() {
        List<String> canonicalDomains = conceptCatalog.concepts().stream()
                .map(SemanticConcept::domainId)
                .distinct()
                .toList();
        List<String> canonicalSubdomains = conceptCatalog.concepts().stream()
                .map(concept ->
                        concept.domainId()
                                + "/"
                                + concept.subdomainId()
                )
                .distinct()
                .toList();

        for (String language : List.of("ru", "kk", "zh", "de", "fr", "es", "pt", "it", "tr", "el")) {
            assertThat(registry.surfaces(language))
                    .extracting(SemanticConceptSurface::domainId)
                    .containsAll(canonicalDomains);

            assertThat(registry.surfaces(language).stream()
                    .map(surface ->
                            surface.domainId()
                                    + "/"
                                    + surface.subdomainId()
                    )
                    .distinct()
                    .toList())
                    .containsExactlyInAnyOrderElementsOf(
                            canonicalSubdomains
                    );
        }
    }

    @Test
    void everyCompletedSurfaceKeepsUsableNormalizedSequences() {
        for (String language :
                List.of(
                        "en", "ru", "kk", "zh", "de", "fr",
                        "es", "pt", "it", "tr", "el"
                )) {
            assertThat(registry.surfaces(language))
                    .as(language)
                    .allSatisfy(surface -> {
                        assertThat(surface.preferredPhrase())
                                .as(surface.conceptId() + "/preferred")
                                .isNotBlank();
                        assertThat(
                                surface.preferredPhrase().split("\\s+")
                        )
                                .as(surface.conceptId() + "/preferred")
                                .isNotEmpty();
                        assertThat(surface.lemmaPhrase())
                                .as(surface.conceptId() + "/lemma")
                                .isNotBlank();
                        assertThat(surface.lemmaPhrase().split("\\s+"))
                                .as(surface.conceptId() + "/lemma")
                                .isNotEmpty();
                        assertThat(surface.stemTokens())
                                .as(surface.conceptId() + "/stems")
                                .isNotEmpty()
                                .allSatisfy(stem ->
                                        assertThat(stem).isNotBlank()
                                );
                    });
        }
    }

    @Test
    void languageVersionsAreIndependent() {
        assertThat(registry.version("en"))
                .isEqualTo("semantic-concepts-en-v2");
        assertThat(registry.version("ru"))
                .isEqualTo("semantic-surfaces-ru-v1");
        assertThat(registry.version("kk"))
                .isEqualTo("semantic-surfaces-kk-v1");
        assertThat(registry.version("zh"))
                .isEqualTo("semantic-surfaces-zh-v1");
        assertThat(registry.version("de"))
                .isEqualTo("semantic-surfaces-de-v1");
        assertThat(registry.version("fr"))
                .isEqualTo("semantic-surfaces-fr-v1");
        assertThat(registry.version("es"))
                .isEqualTo("semantic-surfaces-es-v1");
        assertThat(registry.version("pt"))
                .isEqualTo("semantic-surfaces-pt-v1");
        assertThat(registry.version("it"))
                .isEqualTo("semantic-surfaces-it-v1");
        assertThat(registry.version("tr"))
                .isEqualTo("semantic-surfaces-tr-v1");
        assertThat(registry.version("el"))
                .isEqualTo("semantic-surfaces-el-v1");
    }

    @Test
    void unknownLanguageWithoutPackStaysUnsupported() {
        assertThat(registry.surfaces("ja")).isEmpty();
        assertThat(registry.version("ja")).isNull();
    }
}
