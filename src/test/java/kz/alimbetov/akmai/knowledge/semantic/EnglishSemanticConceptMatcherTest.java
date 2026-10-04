package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EnglishSemanticConceptMatcherTest {

    private final EnglishSemanticConceptMatcher matcher =
            new EnglishSemanticConceptMatcher(
                    new EnglishSemanticConceptCatalog(
                            new SemanticDomainCatalog()
                    )
            );

    @Test
    void matchesHighInformationPhraseWithoutSingleWordAnchor() {
        var matches = matcher.match(
                "The capital adequacy ratio remained above the required threshold."
        );

        assertThat(matches)
                .extracting(SemanticConceptMatch::conceptId)
                .contains(
                        "finance_banking.risk_capital.capital_adequacy_ratio"
                );
    }

    @Test
    void matchesMultipleConceptsAcrossDomains() {
        var matches = matcher.match(
                "A machine learning model estimates climate change projection risk."
        );

        assertThat(matches)
                .extracting(SemanticConceptMatch::domainId)
                .contains(
                        "computer_science_ai",
                        "earth_environmental_science"
                );
    }

    @Test
    void doesNotMatchPartialWordBoundaries() {
        var matches = matcher.match(
                "The database stores unrelated capitalization metadata."
        );

        assertThat(matches)
                .extracting(SemanticConceptMatch::phrase)
                .doesNotContain("capital adequacy ratio");
    }

    @Test
    void phraseWeightReflectsInformationLength() {
        var match = matcher.match(
                        "The team deployed a large language model."
                )
                .stream()
                .filter(value ->
                        value.phrase().equals("large language model")
                )
                .findFirst()
                .orElseThrow();

        assertThat(match.weight()).isEqualTo(3.0);
    }
}
