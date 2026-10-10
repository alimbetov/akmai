package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EnglishSemanticConceptCatalogTest {

    private final SemanticDomainCatalog domains =
            new SemanticDomainCatalog();
    private final EnglishSemanticConceptCatalog catalog =
            new EnglishSemanticConceptCatalog(domains);

    @Test
    void corpusContainsCuratedPhraseConceptsForEveryDomain() {
        assertThat(catalog.version()).isEqualTo("semantic-concepts-en-v2");
        assertThat(catalog.concepts()).hasSize(984);

        Map<String, Long> byDomain = catalog.concepts().stream()
                .collect(Collectors.groupingBy(
                        SemanticConcept::domainId,
                        Collectors.counting()
                ));

        assertThat(byDomain.keySet())
                .containsExactlyInAnyOrderElementsOf(domains.domainIds());
        assertThat(byDomain.get("finance_banking")).isEqualTo(424L);
        assertThat(byDomain.get("insurance")).isEqualTo(224L);
        byDomain.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("finance_banking"))
                .filter(entry -> !entry.getKey().equals("insurance"))
                .forEach(entry -> assertThat(entry.getValue())
                        .as(entry.getKey())
                        .isEqualTo(24L));
    }

    @Test
    void financeBatchesAddOneHundredConceptsPerExistingSubdomain() {
        Map<String, Long> financeBySubdomain = catalog
                .conceptsForDomain("finance_banking")
                .stream()
                .collect(Collectors.groupingBy(
                        SemanticConcept::subdomainId,
                        Collectors.counting()
                ));

        assertThat(financeBySubdomain)
                .containsEntry("lending_credit", 106L)
                .containsEntry("deposits_liquidity", 106L)
                .containsEntry("risk_capital", 106L)
                .containsEntry("payments_compliance", 106L);
    }

    @Test
    void insuranceBatchesAddFiftyConceptsPerExistingSubdomain() {
        Map<String, Long> insuranceBySubdomain = catalog
                .conceptsForDomain("insurance")
                .stream()
                .collect(Collectors.groupingBy(
                        SemanticConcept::subdomainId,
                        Collectors.counting()
                ));

        assertThat(insuranceBySubdomain)
                .containsEntry("underwriting_pricing", 56L)
                .containsEntry("claims_management", 56L)
                .containsEntry("life_health", 56L)
                .containsEntry("property_casualty", 56L);
    }

    @Test
    void representativeInsuranceBatchBConceptsRemainStableAndClassified() {
        assertThat(catalog.require(
                "insurance.underwriting_pricing.technical_premium_calculation"
        ).subdomainId()).isEqualTo("underwriting_pricing");
        assertThat(catalog.require(
                "insurance.claims_management.automated_claims_adjudication"
        ).subdomainId()).isEqualTo("claims_management");
        assertThat(catalog.require(
                "insurance.life_health.prior_authorization_process"
        ).subdomainId()).isEqualTo("life_health");
        assertThat(catalog.require(
                "insurance.property_casualty.directors_officers_liability"
        ).subdomainId()).isEqualTo("property_casualty");
    }

    @Test
    void representativeInsuranceBatchAConceptsRemainStableAndClassified() {
        assertThat(catalog.require(
                "insurance.underwriting_pricing.premium_rate_adequacy"
        ).subdomainId()).isEqualTo("underwriting_pricing");
        assertThat(catalog.require(
                "insurance.claims_management.first_notice_of_loss"
        ).subdomainId()).isEqualTo("claims_management");
        assertThat(catalog.require(
                "insurance.life_health.medical_loss_ratio"
        ).subdomainId()).isEqualTo("life_health");
        assertThat(catalog.require(
                "insurance.property_casualty.probable_maximum_loss"
        ).subdomainId()).isEqualTo("property_casualty");
    }

    @Test
    void representativeBatchDConceptsRemainStableAndClassified() {
        assertThat(catalog.require(
                "finance_banking.lending_credit.borrower_repayment_capacity"
        ).subdomainId()).isEqualTo("lending_credit");
        assertThat(catalog.require(
                "finance_banking.deposits_liquidity.funding_maturity_ladder"
        ).subdomainId()).isEqualTo("deposits_liquidity");
        assertThat(catalog.require(
                "finance_banking.risk_capital.reverse_stress_testing"
        ).subdomainId()).isEqualTo("risk_capital");
        assertThat(catalog.require(
                "finance_banking.payments_compliance.sanctions_evasion_detection"
        ).subdomainId()).isEqualTo("payments_compliance");
    }

    @Test
    void representativeBatchCConceptsRemainStableAndClassified() {
        assertThat(catalog.require(
                "finance_banking.lending_credit.expected_credit_loss"
        ).subdomainId()).isEqualTo("lending_credit");
        assertThat(catalog.require(
                "finance_banking.deposits_liquidity.liquidity_early_warning"
        ).subdomainId()).isEqualTo("deposits_liquidity");
        assertThat(catalog.require(
                "finance_banking.risk_capital.risk_weighted_asset_density"
        ).subdomainId()).isEqualTo("risk_capital");
        assertThat(catalog.require(
                "finance_banking.payments_compliance.mule_account_detection"
        ).subdomainId()).isEqualTo("payments_compliance");
    }

    @Test
    void representativeBatchBConceptsRemainStableAndClassified() {
        assertThat(catalog.require(
                "finance_banking.lending_credit.project_finance_lending"
        ).subdomainId()).isEqualTo("lending_credit");
        assertThat(catalog.require(
                "finance_banking.deposits_liquidity.liquidity_survival_horizon"
        ).subdomainId()).isEqualTo("deposits_liquidity");
        assertThat(catalog.require(
                "finance_banking.risk_capital.expected_shortfall_measure"
        ).subdomainId()).isEqualTo("risk_capital");
        assertThat(catalog.require(
                "finance_banking.payments_compliance.source_of_wealth_verification"
        ).subdomainId()).isEqualTo("payments_compliance");
    }

    @Test
    void primaryConceptsAreMultiWordPhrases() {
        assertThat(catalog.concepts())
                .allSatisfy(concept -> {
                    String[] tokens =
                            concept.preferredPhrase().split("\\s+");
                    assertThat(tokens.length)
                            .as(concept.id())
                            .isBetween(2, 6);
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
        SemanticConcept concept = catalog.concepts().stream()
                .filter(value ->
                        value.preferredPhrase()
                                .equals("capital adequacy ratio")
                )
                .findFirst()
                .orElseThrow();

        assertThat(concept.id()).isEqualTo(
                "finance_banking.risk_capital.capital_adequacy_ratio"
        );
        assertThat(concept.domainId()).isEqualTo("finance_banking");
        assertThat(concept.subdomainId()).isEqualTo("risk_capital");
    }
}
