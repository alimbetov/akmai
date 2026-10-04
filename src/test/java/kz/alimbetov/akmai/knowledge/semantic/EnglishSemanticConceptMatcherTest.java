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
                .filteredOn(match ->
                        match.conceptId().equals(
                                "finance_banking.risk_capital.capital_adequacy_ratio"
                        )
                )
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.matchMode())
                            .isEqualTo(SemanticMatchMode.EXACT);
                    assertThat(match.weight()).isEqualTo(3.0);
                });
    }

    @Test
    void matchesInflectedPhraseThroughLemmaSequence() {
        var match = matcher.match(
                        "The bank reported lower risk weighted asset exposure."
                )
                .stream()
                .filter(value ->
                        value.conceptId().equals(
                                "finance_banking.risk_capital.risk_weighted_assets"
                        )
                )
                .findFirst()
                .orElseThrow();

        assertThat(match.matchMode())
                .isEqualTo(SemanticMatchMode.LEMMA);
        assertThat(match.weight()).isEqualTo(2.7);
    }

    @Test
    void matchesDerivationalVariantOnlyThroughBoundedStemSequence() {
        var match = matcher.match(
                        "The system supports payment fraud detecting workflows."
                )
                .stream()
                .filter(value ->
                        value.conceptId().equals(
                                "finance_banking.payments_compliance.payment_fraud_detection"
                        )
                )
                .findFirst()
                .orElseThrow();

        assertThat(match.matchMode())
                .isEqualTo(SemanticMatchMode.STEM);
        assertThat(match.weight()).isEqualTo(1.95);
    }

    @Test
    void isolatedRootCannotTriggerPhraseConcept() {
        var matches = matcher.match(
                "Payment risk remains elevated."
        );

        assertThat(matches)
                .extracting(SemanticConceptMatch::conceptId)
                .doesNotContain(
                        "finance_banking.payments_compliance.payment_fraud_detection"
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
