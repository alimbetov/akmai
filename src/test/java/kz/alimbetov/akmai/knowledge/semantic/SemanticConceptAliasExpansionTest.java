package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SemanticConceptAliasExpansionTest {

    private static final List<String> CORE_LANGUAGES =
            List.of("en", "ru", "kk");
    private static final List<String> EXPANDED_LANGUAGES =
            List.of("zh", "de", "fr", "es", "pt", "it", "tr", "el");
    private static final List<String> PRODUCTION_LANGUAGES =
            List.of(
                    "en", "ru", "kk", "zh", "de", "fr",
                    "es", "pt", "it", "tr", "el"
            );
    private static final int BASE_PHRASES_PER_DOMAIN = 24;
    private static final int REQUIRED_ADDITIONAL_PHRASES =
            (int) Math.ceil(BASE_PHRASES_PER_DOMAIN * 0.20);

    private final SemanticDomainCatalog domainCatalog =
            new SemanticDomainCatalog();
    private final EnglishSemanticConceptCatalog conceptCatalog =
            new EnglishSemanticConceptCatalog(domainCatalog);

    @Test
    void coreAliasPackAddsTwentyPercentForEnRuKk()
            throws IOException {
        assertAliasPack(
                load("semantic/concept-aliases-core-v1.yaml"),
                "semantic-concept-aliases-core-v1",
                CORE_LANGUAGES
        );
    }

    @Test
    void globalAliasPackAddsTwentyPercentForRemainingLanguages()
            throws IOException {
        assertAliasPack(
                load("semantic/concept-aliases-global-v1.yaml"),
                "semantic-concept-aliases-global-v1",
                EXPANDED_LANGUAGES
        );
    }

    @Test
    void everyGlobalAliasIsAppliedToItsCanonicalSurface()
            throws IOException {
        SemanticMorphologyRegistry morphology =
                SemanticTestMorphology.registry();
        SemanticConceptSurfaceRegistry registry =
                new SemanticConceptSurfaceRegistry(
                        conceptCatalog,
                        morphology
                );
        SemanticConceptAliasDefinition definition =
                load("semantic/concept-aliases-global-v1.yaml");

        for (SemanticConceptAliasDefinition.Entry entry :
                definition.entries()) {
            for (String language : EXPANDED_LANGUAGES) {
                SemanticConceptSurface surface =
                        registry.surfaces(language).stream()
                                .filter(candidate -> candidate.conceptId()
                                        .equals(entry.conceptId()))
                                .findFirst()
                                .orElseThrow();
                SemanticMorphologyNormalizer normalizer =
                        morphology.require(language);

                for (String alias : entry.aliases().get(language)) {
                    String normalized = String.join(
                            " ",
                            normalizer.normalizeTokens(alias)
                    );
                    assertThat(surface.aliases())
                            .as(entry.conceptId() + "/" + language)
                            .contains(normalized);
                }
            }
        }
    }

    @Test
    void aliasesDoNotCreateCrossConceptSurfaceCollisions() {
        SemanticMorphologyRegistry morphology =
                SemanticTestMorphology.registry();
        SemanticConceptSurfaceRegistry registry =
                new SemanticConceptSurfaceRegistry(
                        conceptCatalog,
                        morphology
                );

        for (String language : PRODUCTION_LANGUAGES) {
            Map<String, Set<String>> owners = new LinkedHashMap<>();
            for (SemanticConceptSurface surface :
                    registry.surfaces(language)) {
                register(
                        owners,
                        surface.preferredPhrase(),
                        surface.conceptId()
                );
                for (String alias : surface.aliases()) {
                    register(owners, alias, surface.conceptId());
                }
            }

            assertThat(owners.entrySet().stream()
                    .filter(entry -> entry.getValue().size() > 1)
                    .toList())
                    .as(language + " semantic surface collisions")
                    .isEmpty();
        }
    }

    @Test
    void aliasesResolveToStableCanonicalConceptsAcrossAllLanguages() {
        SemanticMorphologyRegistry morphology =
                SemanticTestMorphology.registry();
        SemanticConceptSurfaceRegistry registry =
                new SemanticConceptSurfaceRegistry(
                        conceptCatalog,
                        morphology
                );
        SemanticConceptMatcher matcher =
                new SemanticConceptMatcher(registry, morphology);

        assertConcept(
                matcher,
                "borrower credit evaluation",
                "en",
                "finance_banking.lending_credit.credit_risk_assessment"
        );
        assertConcept(
                matcher,
                "проверка значимости гипотез",
                "ru",
                "mathematics_statistics.probability_statistics.statistical_hypothesis_testing"
        );
        assertConcept(
                matcher,
                "жүк тасымалы желісі",
                "kk",
                "transport_logistics.freight_transport.freight_transportation_network"
        );
        assertConcept(
                matcher,
                "供应链中断",
                "zh",
                "transport_logistics.supply_chain.supply_chain_disruption"
        );
        assertConcept(
                matcher,
                "Klimawandelprognose",
                "de",
                "earth_environmental_science.climate_science.climate_change_projection"
        );
        assertConcept(
                matcher,
                "essai clinique randomisé",
                "fr",
                "medicine_pharmacology.clinical_trials.randomized_controlled_trial"
        );
        assertConcept(
                matcher,
                "optimización con restricciones",
                "es",
                "mathematics_statistics.optimization.constrained_optimization_problem"
        );
        assertConcept(
                matcher,
                "controle de segurança alimentar",
                "pt",
                "agriculture_food.food_safety.food_safety_management"
        );
        assertConcept(
                matcher,
                "analisi della struttura cristallina",
                "it",
                "chemistry_materials.materials_science.crystal_structure_analysis"
        );
        assertConcept(
                matcher,
                "graf arama algoritması",
                "tr",
                "computer_science_ai.algorithms_data_structures.graph_traversal_algorithm"
        );
        assertConcept(
                matcher,
                "θεωρία ομαδικής ταυτότητας",
                "el",
                "psychology_sociology.social_psychology.social_identity_theory"
        );
    }

    private void assertAliasPack(
            SemanticConceptAliasDefinition definition,
            String expectedVersion,
            List<String> languages
    ) {
        assertThat(definition.version()).isEqualTo(expectedVersion);
        assertThat(REQUIRED_ADDITIONAL_PHRASES).isEqualTo(5);
        assertThat(definition.entries()).hasSize(80);
        assertThat(definition.entries())
                .extracting(SemanticConceptAliasDefinition.Entry::conceptId)
                .doesNotHaveDuplicates();

        Map<String, Long> conceptsPerDomain = definition.entries().stream()
                .collect(Collectors.groupingBy(
                        entry -> conceptCatalog.require(
                                entry.conceptId()
                        ).domainId(),
                        Collectors.counting()
                ));

        assertThat(conceptsPerDomain).hasSize(16);
        for (String domainId : domainCatalog.domainIds()) {
            assertThat(conceptsPerDomain.get(domainId))
                    .as(domainId)
                    .isEqualTo((long) REQUIRED_ADDITIONAL_PHRASES);
        }

        for (SemanticConceptAliasDefinition.Entry entry :
                definition.entries()) {
            assertThat(entry.aliases().keySet())
                    .as(entry.conceptId())
                    .containsExactlyInAnyOrderElementsOf(languages);
            for (String language : languages) {
                assertThat(entry.aliases().get(language))
                        .as(entry.conceptId() + "/" + language)
                        .hasSize(1)
                        .allSatisfy(alias ->
                                assertThat(alias).isNotBlank()
                        );
            }
        }
    }

    private void register(
            Map<String, Set<String>> owners,
            String phrase,
            String conceptId
    ) {
        owners.computeIfAbsent(
                phrase,
                ignored -> new LinkedHashSet<>()
        ).add(conceptId);
    }

    private void assertConcept(
            SemanticConceptMatcher matcher,
            String phrase,
            String language,
            String expectedConceptId
    ) {
        assertThat(matcher.match(phrase, language))
                .extracting(SemanticConceptMatch::conceptId)
                .contains(expectedConceptId);
    }

    private SemanticConceptAliasDefinition load(String resource)
            throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (var input = new ClassPathResource(resource).getInputStream()) {
            return mapper.readValue(
                    input,
                    SemanticConceptAliasDefinition.class
            );
        }
    }
}
