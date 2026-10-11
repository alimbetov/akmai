package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class SemanticConceptSurfaceRegistryTest {

    private static final int MULTILINGUAL_V1_BASELINE = 384;
    private static final int ROLLOUT_DOMAIN_COUNT = 7;
    private static final int ROLLOUT_BATCH_COUNT = 4;
    private static final int SURFACES_PER_BATCH = 100;
    private static final int ROLLOUT_SURFACE_COUNT =
            MULTILINGUAL_V1_BASELINE
                    + ROLLOUT_DOMAIN_COUNT
                    * ROLLOUT_BATCH_COUNT
                    * SURFACES_PER_BATCH;
    private static final List<String> ROLLOUT_RESOURCE_DOMAINS = List.of(
            "finance",
            "insurance",
            "energy-utilities",
            "oil-gas-mining",
            "manufacturing",
            "construction-real-estate",
            "transport-logistics"
    );

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

        for (String language : List.of("ru", "kk")) {
            assertThat(registry.surfaces(language))
                    .as(language)
                    .hasSize(ROLLOUT_SURFACE_COUNT)
                    .allSatisfy(surface ->
                            assertThat(canonicalIds)
                                    .contains(surface.conceptId())
                    );
        }

        for (String language : List.of("zh", "de", "fr", "es", "pt", "it", "tr", "el")) {
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
    void everyRuAndKkRolloutResourceIsRegisteredForRuntimeLoading()
            throws IOException {
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver();

        Set<String> discovered = Arrays.stream(resolver.getResources(
                        "classpath*:semantic/concept-surfaces-*-batch-*-v2.yaml"
                ))
                .map(Resource::getFilename)
                .filter(name -> name != null
                        && (name.startsWith("concept-surfaces-ru-")
                        || name.startsWith("concept-surfaces-kk-")))
                .map(name -> "semantic/" + name)
                .collect(Collectors.toSet());

        Set<String> configured =
                SemanticConceptSurfaceRegistry.multilingualResources().stream()
                        .filter(resource ->
                                resource.startsWith("semantic/concept-surfaces-ru-")
                                        || resource.startsWith(
                                        "semantic/concept-surfaces-kk-"
                                ))
                        .filter(resource -> resource.contains("-batch-"))
                        .collect(Collectors.toSet());

        assertThat(discovered).isNotEmpty();
        assertThat(configured)
                .containsExactlyInAnyOrderElementsOf(discovered);
    }

    @Test
    void russianFinanceBatchesReachFullCanonicalDomainParity() {
        assertFullDomainParity(
                "finance_banking",
                List.of(
                        "finance_banking.lending_credit.borrower_repayment_capacity",
                        "finance_banking.deposits_liquidity.insured_deposit_protection",
                        "finance_banking.risk_capital.capital_adequacy_planning",
                        "finance_banking.payments_compliance.customer_onboarding_screening"
                )
        );
    }

    @Test
    void russianInsuranceBatchesReachFullCanonicalDomainParity() {
        List<SemanticConceptSurface> insuranceSurfaces = registry.surfaces("ru").stream()
                .filter(surface -> surface.domainId().equals("insurance"))
                .toList();

        assertThat(insuranceSurfaces).hasSize(424);

        assertThat(insuranceSurfaces.stream()
                .collect(Collectors.groupingBy(
                        SemanticConceptSurface::subdomainId,
                        Collectors.counting()
                )))
                .containsEntry("underwriting_pricing", 106L)
                .containsEntry("claims_management", 106L)
                .containsEntry("life_health", 106L)
                .containsEntry("property_casualty", 106L);

        assertThat(insuranceSurfaces)
                .extracting(SemanticConceptSurface::conceptId)
                .contains(
                        "insurance.underwriting_pricing.risk_appetite_calibration",
                        "insurance.claims_management.claim_intake_automation",
                        "insurance.life_health.life_underwriting_evidence",
                        "insurance.property_casualty.property_exposure_schedule"
                );
    }

    private void assertFullDomainParity(
            String domainId,
            List<String> representativeConceptIds
    ) {
        List<SemanticConceptSurface> surfaces = registry.surfaces("ru").stream()
                .filter(surface -> surface.domainId().equals(domainId))
                .toList();
        assertThat(surfaces).hasSize(424);
        assertThat(surfaces)
                .extracting(SemanticConceptSurface::conceptId)
                .containsAll(representativeConceptIds);
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
                .isEqualTo(expectedRolloutVersion("ru"));
        assertThat(registry.version("kk"))
                .isEqualTo(expectedRolloutVersion("kk"));
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

    private static String expectedRolloutVersion(String language) {
        StringBuilder version = new StringBuilder(
                "semantic-surfaces-" + language + "-v1"
        );
        for (String domain : ROLLOUT_RESOURCE_DOMAINS) {
            for (char batch = 'a'; batch <= 'd'; batch++) {
                version.append("+semantic-surfaces-")
                        .append(language)
                        .append('-')
                        .append(domain)
                        .append("-batch-")
                        .append(batch)
                        .append("-v2");
            }
        }
        return version.toString();
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
