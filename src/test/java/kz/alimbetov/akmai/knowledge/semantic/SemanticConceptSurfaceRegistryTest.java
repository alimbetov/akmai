package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticConceptSurfaceRegistryTest {

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
    void everyCanonicalConceptGetsExactlyOneSurfacePerCompletedLanguage() {
        List<String> canonicalIds = conceptCatalog.concepts().stream()
                .map(SemanticConcept::id)
                .toList();

        for (String language : List.of("en", "ru", "kk", "zh", "de", "fr", "es")) {
            assertThat(registry.surfaces(language))
                    .as(language)
                    .hasSameSizeAs(conceptCatalog.concepts());

            assertThat(registry.surfaces(language))
                    .as(language)
                    .extracting(SemanticConceptSurface::conceptId)
                    .containsExactlyInAnyOrderElementsOf(canonicalIds);
        }
    }

    @Test
    void completedSurfacePacksPreserveAllDomainsAndSubdomains() {
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

        for (String language : List.of("ru", "kk", "zh", "de", "fr", "es")) {
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
    void everyCompletedSurfaceKeepsMultiTokenLemmaAndStemSequences() {
        for (String language : List.of("en", "ru", "kk", "zh")) {
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
    void completedLanguageVersionsAreIndependent() {
        assertThat(registry.version("en"))
                .isEqualTo("semantic-concepts-en-v1");
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
    }

    @Test
    void languagesWithoutTranslatedConceptPacksStayUnsupported() {
        assertThat(registry.surfaces("pt")).isEmpty();
        assertThat(registry.surfaces("it")).isEmpty();
        assertThat(registry.version("pt")).isNull();
    }
}
