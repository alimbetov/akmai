package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SemanticConceptSurfaceRegistryTest {

    private static final int MULTILINGUAL_V1_BASELINE = 384;
    private static final int RU_FINANCE_BATCH_A_ADDITIONS = 100;
    private static final int RU_FINANCE_BATCH_B_ADDITIONS = 100;

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
    void englishSurfacesTrackCurrentCanonicalCorpusWhileTranslatedPacksStayVersioned() {
        assertThat(registry.surfaces("en"))
                .hasSameSizeAs(conceptCatalog.concepts());

        Set<String> canonicalIds = conceptCatalog.concepts().stream()
                .map(SemanticConcept::id)
                .collect(Collectors.toSet());

        assertThat(registry.surfaces("ru"))
                .hasSize(
                        MULTILINGUAL_V1_BASELINE
                                + RU_FINANCE_BATCH_A_ADDITIONS
                                + RU_FINANCE_BATCH_B_ADDITIONS
                )
                .allSatisfy(surface ->
                        assertThat(canonicalIds)
                                .contains(surface.conceptId())
                );

        for (String language : List.of("kk", "zh", "de", "fr", "es", "pt", "it", "tr", "el")) {
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
    void russianFinanceBatchesAddExactlyTwoHundredCanonicalSurfaces() {
        List<SemanticConceptSurface> financeSurfaces = registry.surfaces("ru").stream()
                .filter(surface -> surface.domainId().equals("finance_banking"))
                .toList();

        assertThat(financeSurfaces).hasSize(224);

        assertThat(financeSurfaces.stream()
                .collect(Collectors.groupingBy(
                        SemanticConceptSurface::subdomainId,
                        Collectors.counting()
                )))
                .containsEntry("lending_credit", 56L)
                .containsEntry("deposits_liquidity", 56L)
                .containsEntry("risk_capital", 56L)
                .containsEntry("payments_compliance", 56L);

        assertThat(financeSurfaces)
                .extracting(SemanticConceptSurface::conceptId)
                .contains(
                        "finance_banking.lending_credit.commercial_real_estate_lending",
                        "finance_banking.deposits_liquidity.current_account_balance",
                        "finance_banking.risk_capital.risk_weighted_asset_density",
                        "finance_banking.payments_compliance.customer_onboarding_controls"
                );
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
    void languageVersionsAreIndependentAndAdditivePacksAreVisible() {
        assertThat(registry.version("en"))
                .isEqualTo("semantic-concepts-en-v2");
        assertThat(registry.version("ru"))
                .isEqualTo(
                        "semantic-surfaces-ru-v1+semantic-surfaces-ru-finance-batch-a-v2+semantic-surfaces-ru-finance-batch-b-v2"
                );
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
    void additivePacksCannotOverrideExistingConceptSurface() {
        String conceptId = "finance_banking.lending_credit.credit_risk_assessment";
        MultilingualConceptSurfaceDefinition first =
                definition("ru-test-a", conceptId, "оценка кредитного риска");
        MultilingualConceptSurfaceDefinition duplicate =
                definition("ru-test-b", conceptId, "повторная оценка риска");

        assertThatThrownBy(() -> new SemanticConceptSurfaceRegistry(
                conceptCatalog,
                morphology,
                List.of(first, duplicate)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate semantic surface for ru/" + conceptId);
    }

    @Test
    void unknownLanguageWithoutPackStaysUnsupported() {
        assertThat(registry.surfaces("ja")).isEmpty();
        assertThat(registry.version("ja")).isNull();
    }

    private static MultilingualConceptSurfaceDefinition definition(
            String version,
            String conceptId,
            String phrase
    ) {
        return new MultilingualConceptSurfaceDefinition(
                version,
                List.of(new MultilingualConceptSurfaceDefinition.LanguageSurfaces(
                        "ru",
                        List.of(new MultilingualConceptSurfaceDefinition.Surface(
                                conceptId,
                                phrase,
                                List.of()
                        ))
                ))
        );
    }
}
