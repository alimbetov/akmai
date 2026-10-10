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
        assertThat(catalog.concepts()).hasSize(684);

        Map<String, Long> byDomain = catalog.concepts().stream()
                .collect(Collectors.groupingBy(
                        SemanticConcept::domainId,
                        Collectors.counting()
                ));

        assertThat(byDomain.keySet())
                .containsExactlyInAnyOrderElementsOf(domains.domainIds());
        assertThat(byDomain.get("finance_banking")).isEqualTo(324L);
        byDomain.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("finance_banking"))
                .forEach(entry -> assertThat(entry.getValue())
                        .as(entry.getKey())
                        .isEqualTo(24L));
    }

    @Test
    void financeBatchesAddSeventyFiveConceptsPerExistingSubdomain() {
        Map<String, Long> financeBySubdomain = catalog
                .conceptsForDomain("finance_banking")
                .stream()
                .collect(Collectors.groupingBy(
                        SemanticConcept::subdomainId,
                        Collectors.counting()
                ));

        assertThat(financeBySubdomain)
                .containsEntry("lending_credit", 81L)
                .containsEntry("deposits_liquidity", 81L)
                .containsEntry("risk_capital", 81L)
                .containsEntry("payments_compliance", 81L);
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
