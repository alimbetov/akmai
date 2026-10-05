package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EnglishSemanticConceptCatalogTest {

    private final SemanticDomainCatalog domains =
            new SemanticDomainCatalog();
    private final EnglishSemanticConceptCatalog catalog =
            new EnglishSemanticConceptCatalog(domains);

    @Test
    void corpusContainsCuratedPhraseConceptsForEveryDomain() {
        assertThat(catalog.concepts()).hasSize(384);

        Map<String, Long> byDomain = catalog.concepts().stream()
                .collect(Collectors.groupingBy(
                        SemanticConcept::domainId,
                        Collectors.counting()
                ));

        assertThat(byDomain.keySet())
                .containsExactlyInAnyOrderElementsOf(domains.domainIds());
        byDomain.forEach((domain, count) ->
                assertThat(count)
                        .as(domain)
                        .isEqualTo(24L)
        );
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
