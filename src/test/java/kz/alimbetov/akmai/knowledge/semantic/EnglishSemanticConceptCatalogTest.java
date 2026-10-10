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
            Map.entry("energy_utilities", 424L),
            Map.entry("oil_gas_mining", 424L),
            Map.entry("manufacturing", 424L),
            Map.entry("construction_real_estate", 424L),
            Map.entry("transport_logistics", 424L),
            Map.entry("agriculture_food", 424L),
            Map.entry("mathematics_statistics", 224L),
            Map.entry("physics_astronomy", 224L),
            Map.entry("chemistry_materials", 424L),
            Map.entry("biology_genetics", 424L),
            Map.entry("computer_science_ai", 424L),
            Map.entry("medicine_pharmacology", 424L),
            Map.entry("earth_environmental_science", 224L),
            Map.entry("psychology_sociology", 224L)
    );

    private final SemanticDomainCatalog domains = new SemanticDomainCatalog();
    private final EnglishSemanticConceptCatalog catalog = new EnglishSemanticConceptCatalog(domains);

    @Test
    void corpusContainsExpectedCuratedConceptCounts() {
        assertThat(catalog.version()).isEqualTo("semantic-concepts-en-v2");
        assertThat(catalog.concepts()).hasSize(5984);

        Map<String, Long> byDomain = catalog.concepts().stream()
                .collect(Collectors.groupingBy(
                        SemanticConcept::domainId,
                        Collectors.counting()
                ));

        assertThat(byDomain).containsExactlyInAnyOrderEntriesOf(EXPECTED_DOMAIN_COUNTS);
        assertThat(byDomain.keySet()).containsExactlyInAnyOrderElementsOf(domains.domainIds());
    }

    @Test
    void completedDomainsContainOneHundredNewConceptsPerSubdomain() {
        assertSubdomainCounts("finance_banking", 106L);
        assertSubdomainCounts("insurance", 106L);
        assertSubdomainCounts("energy_utilities", 106L);
        assertSubdomainCounts("oil_gas_mining", 106L);
        assertSubdomainCounts("manufacturing", 106L);
        assertSubdomainCounts("construction_real_estate", 106L);
        assertSubdomainCounts("transport_logistics", 106L);
        assertSubdomainCounts("agriculture_food", 106L);
        assertSubdomainCounts("computer_science_ai", 106L);
        assertSubdomainCounts("medicine_pharmacology", 106L);
        assertSubdomainCounts("biology_genetics", 106L);
        assertSubdomainCounts("chemistry_materials", 106L);
    }

    @Test
    void stageFourBatchABDomainsContainFiftyNewConceptsPerSubdomain() {
        assertSubdomainCounts("mathematics_statistics", 56L);
        assertSubdomainCounts("physics_astronomy", 56L);
        assertSubdomainCounts("earth_environmental_science", 56L);
        assertSubdomainCounts("psychology_sociology", 56L);
    }

    @Test
    void representativeStageFourBatchBConceptsRemainStableAndClassified() {
        assertConcept("mathematics_statistics.probability_statistics.bayesian_hierarchical_model", "probability_statistics");
        assertConcept("physics_astronomy.astrophysics.gravitational_wave_background", "astrophysics");
        assertConcept("earth_environmental_science.hydrology.groundwater_vulnerability_mapping", "hydrology");
        assertConcept("psychology_sociology.sociology_methods.structural_equation_modeling", "sociology_methods");
    }

    @Test
    void representativeStageFourBatchAConceptsRemainStableAndClassified() {
        assertConcept("mathematics_statistics.probability_statistics.bootstrap_resampling_method", "probability_statistics");
        assertConcept("physics_astronomy.astrophysics.gravitational_lensing_analysis", "astrophysics");
        assertConcept("earth_environmental_science.climate_science.climate_sensitivity_estimate", "climate_science");
        assertConcept("psychology_sociology.cognitive_psychology.executive_function_assessment", "cognitive_psychology");
    }

    @Test
    void representativeScienceTechnologyBatchDConceptsRemainStableAndClassified() {
        assertConcept("computer_science_ai.software_systems.linearizability_verification_method", "software_systems");
        assertConcept("medicine_pharmacology.pharmacology.therapeutic_concentration_monitoring", "pharmacology");
        assertConcept("biology_genetics.genetics.homologous_repair_deficiency", "genetics");
        assertConcept("chemistry_materials.materials_science.damage_tolerance_analysis", "materials_science");
    }

    @Test
    void representativeScienceTechnologyBatchCConceptsRemainStableAndClassified() {
        assertConcept("computer_science_ai.software_systems.event_sourcing_pattern", "software_systems");
        assertConcept("medicine_pharmacology.clinical_trials.platform_trial_design", "clinical_trials");
        assertConcept("biology_genetics.genetics.local_ancestry_inference", "genetics");
        assertConcept("chemistry_materials.materials_science.stress_corrosion_cracking", "materials_science");
    }

    @Test
    void representativeScienceTechnologyBatchBConceptsRemainStableAndClassified() {
        assertConcept("computer_science_ai.software_systems.fencing_token_protocol", "software_systems");
        assertConcept("medicine_pharmacology.epidemiology.target_trial_emulation", "epidemiology");
        assertConcept("biology_genetics.genetics.mutational_signature_analysis", "genetics");
        assertConcept("chemistry_materials.materials_science.grain_boundary_engineering", "materials_science");
    }

    @Test
    void representativeScienceTechnologyBatchAConceptsRemainStableAndClassified() {
        assertConcept("computer_science_ai.machine_learning.model_drift_detection", "machine_learning");
        assertConcept("medicine_pharmacology.clinical_trials.adaptive_trial_design", "clinical_trials");
        assertConcept("biology_genetics.genetics.polygenic_risk_score", "genetics");
        assertConcept("chemistry_materials.materials_science.fracture_toughness_testing", "materials_science");
    }

    @Test
    void representativeStageTwoBatchDConceptsRemainStableAndClassified() {
        assertConcept("energy_utilities.power_grid.probabilistic_load_flow", "power_grid");
        assertConcept("oil_gas_mining.midstream_operations.high_consequence_area", "midstream_operations");
        assertConcept("manufacturing.maintenance_reliability.lifecycle_maintenance_cost", "maintenance_reliability");
        assertConcept("construction_real_estate.project_controls.completion_probability_estimate", "project_controls");
        assertConcept("transport_logistics.supply_chain.total_landed_cost", "supply_chain");
        assertConcept("agriculture_food.food_safety.traceability_response_time", "food_safety");
    }

    @Test
    void representativeStageTwoBatchCConceptsRemainStableAndClassified() {
        assertConcept("energy_utilities.power_grid.state_estimation_analysis", "power_grid");
        assertConcept("oil_gas_mining.upstream_operations.reservoir_material_balance", "upstream_operations");
        assertConcept("manufacturing.maintenance_reliability.vibration_spectrum_analysis", "maintenance_reliability");
        assertConcept("construction_real_estate.project_controls.integrated_master_schedule", "project_controls");
        assertConcept("transport_logistics.supply_chain.demand_sensing_process", "supply_chain");
        assertConcept("agriculture_food.food_safety.food_authenticity_testing", "food_safety");
    }

    @Test
    void representativeStageTwoBatchBConceptsRemainStableAndClassified() {
        assertConcept("energy_utilities.power_grid.voltage_stability_analysis", "power_grid");
        assertConcept("oil_gas_mining.mining_operations.cutoff_grade_optimization", "mining_operations");
        assertConcept("manufacturing.maintenance_reliability.weibull_failure_analysis", "maintenance_reliability");
        assertConcept("construction_real_estate.project_controls.time_impact_analysis", "project_controls");
        assertConcept("transport_logistics.warehousing_distribution.dynamic_slotting_policy", "warehousing_distribution");
        assertConcept("agriculture_food.food_safety.authenticity_risk_assessment", "food_safety");
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
