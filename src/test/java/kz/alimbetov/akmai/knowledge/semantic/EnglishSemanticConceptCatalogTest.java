package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EnglishSemanticConceptCatalogTest {

    private static final Map<String, Long> EXPECTED_DOMAIN_COUNTS = Map.ofEntries(
            Map.entry("finance_banking", 424L),
            Map.entry("insurance", 424L),
            Map.entry("energy_utilities", 224L),
            Map.entry("oil_gas_mining", 224L),
            Map.entry("manufacturing", 224L),
            Map.entry("construction_real_estate", 224L),
            Map.entry("transport_logistics", 224L),
            Map.entry("agriculture_food", 224L),
            Map.entry("mathematics_statistics", 24L),
            Map.entry("physics_astronomy", 24L),
            Map.entry("chemistry_materials", 24L),
            Map.entry("biology_genetics", 24L),
            Map.entry("computer_science_ai", 24L),
            Map.entry("medicine_pharmacology", 24L),
            Map.entry("earth_environmental_science", 24L),
            Map.entry("psychology_sociology", 24L)
    );

    private final SemanticDomainCatalog domains = new SemanticDomainCatalog();
    private final EnglishSemanticConceptCatalog catalog = new EnglishSemanticConceptCatalog(domains);

    @Test
    void corpusContainsExpectedCuratedConceptCounts() {
        assertThat(catalog.version()).isEqualTo("semantic-concepts-en-v2");
        assertThat(catalog.concepts()).hasSize(2384);

        Map<String, Long> byDomain = catalog.concepts().stream()
                .collect(Collectors.groupingBy(
                        SemanticConcept::domainId,
                        Collectors.counting()
                ));

        assertThat(byDomain).containsExactlyInAnyOrderEntriesOf(EXPECTED_DOMAIN_COUNTS);
        assertThat(byDomain.keySet()).containsExactlyInAnyOrderElementsOf(domains.domainIds());
    }

    @Test
    void financeAndInsuranceContainOneHundredNewConceptsPerSubdomain() {
        assertSubdomainCounts("finance_banking", 106L);
        assertSubdomainCounts("insurance", 106L);
    }

    @Test
    void stageTwoDomainsContainFiftyNewConceptsPerSubdomain() {
        assertSubdomainCounts("energy_utilities", 56L);
        assertSubdomainCounts("oil_gas_mining", 56L);
        assertSubdomainCounts("manufacturing", 56L);
        assertSubdomainCounts("construction_real_estate", 56L);
        assertSubdomainCounts("transport_logistics", 56L);
        assertSubdomainCounts("agriculture_food", 56L);
    }

    @Test
    void representativeStageTwoBatchBConceptsRemainStableAndClassified() {
        assertConcept("energy_utilities.power_grid.voltage_stability_analysis", "power_grid");
        assertConcept("oil_gas_mining.mining_operations.cutoff_grade_optimization", "mining_operations");
        assertConcept("manufacturing.maintenance_reliability.weibull_failure_analysis", "maintenance_reliability");
        assertConcept("construction_real_estate.project_controls.time_impact_analysis", "project_controls");
        assertConcept("transport_logistics.warehousing_distribution.warehouse_slotting_optimization", "warehousing_distribution");
        assertConcept("agriculture_food.food_safety.food_fraud_vulnerability", "food_safety");
    }

    @Test
    void representativeStageTwoBatchAConceptsRemainStableAndClassified() {
        assertConcept("insurance.underwriting_pricing.risk_appetite_calibration", "underwriting_pricing");
        assertConcept("energy_utilities.power_grid.grid_contingency_analysis", "power_grid");
        assertConcept("oil_gas_mining.upstream_operations.managed_pressure_drilling", "upstream_operations");
        assertConcept("manufacturing.quality_management.measurement_system_analysis", "quality_management");
        assertConcept("construction_real_estate.project_controls.critical_path_analysis", "project_controls");
        assertConcept("transport_logistics.supply_chain.supply_chain_visibility", "supply_chain");
        assertConcept("agriculture_food.food_safety.critical_control_point", "food_safety");
    }

    @Test
    void primaryConceptsAreMultiWordPhrases() {
        assertThat(catalog.concepts()).allSatisfy(concept -> {
            String[] tokens = concept.preferredPhrase().split("\\s+");
            assertThat(tokens.length).as(concept.id()).isBetween(2, 6);
        });
    }

    @Test
    void canonicalPhrasesAreGloballyUniqueAfterNormalization() {
        Set<String> normalized = catalog.concepts().stream()
                .map(SemanticConcept::preferredPhrase)
                .map(EnglishSemanticConceptCatalog::normalizePhrase)
                .collect(Collectors.toSet());

        assertThat(normalized).hasSize(catalog.concepts().size());
    }

    @Test
    void canonicalIdsRemainLanguageIndependentAndStable() {
        SemanticConcept concept = catalog.require(
                "finance_banking.risk_capital.capital_adequacy_ratio"
        );
        assertThat(concept.preferredPhrase()).isEqualTo("capital adequacy ratio");
        assertThat(concept.domainId()).isEqualTo("finance_banking");
        assertThat(concept.subdomainId()).isEqualTo("risk_capital");
    }

    private void assertSubdomainCounts(String domainId, long expected) {
        Map<String, Long> bySubdomain = catalog.conceptsForDomain(domainId).stream()
                .collect(Collectors.groupingBy(
                        SemanticConcept::subdomainId,
                        Collectors.counting()
                ));
        assertThat(bySubdomain).hasSize(4);
        assertThat(bySubdomain.values()).allSatisfy(count -> assertThat(count).isEqualTo(expected));
    }

    private void assertConcept(String id, String subdomainId) {
        assertThat(catalog.require(id).subdomainId()).isEqualTo(subdomainId);
    }
}
