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
    private static final int BASE_PHRASES_PER_DOMAIN = 24;
    private static final int REQUIRED_ADDITIONAL_PHRASES =
            (int) Math.ceil(BASE_PHRASES_PER_DOMAIN * 0.20);

    private final SemanticDomainCatalog domainCatalog =
            new SemanticDomainCatalog();
    private final EnglishSemanticConceptCatalog conceptCatalog =
            new EnglishSemanticConceptCatalog(domainCatalog);

    @Test
    void addsAtLeastTwentyPercentPhrasesToEveryDomainAndCoreLanguage()
            throws IOException {
        SemanticConceptAliasDefinition definition = load();

        assertThat(definition.version())
                .isEqualTo("semantic-concept-aliases-core-v1");
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
                    .containsExactlyInAnyOrderElementsOf(CORE_LANGUAGES);
            for (String language : CORE_LANGUAGES) {
                assertThat(entry.aliases().get(language))
                        .as(entry.conceptId() + "/" + language)
                        .hasSize(1)
                        .allSatisfy(alias ->
                                assertThat(alias).isNotBlank()
                        );
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

        for (String language : CORE_LANGUAGES) {
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
    void aliasesResolveToStableCanonicalConceptsAcrossCoreLanguages() {
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

    private SemanticConceptAliasDefinition load() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (var input = new ClassPathResource(
                "semantic/concept-aliases-core-v1.yaml"
        ).getInputStream()) {
            return mapper.readValue(
                    input,
                    SemanticConceptAliasDefinition.class
            );
        }
    }
}
