package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticAmbiguityRegistryTest {

    private final EnglishSemanticConceptCatalog catalog =
            new EnglishSemanticConceptCatalog(new SemanticDomainCatalog());

    @Test
    void loadsCuratedAmbiguitiesAgainstCanonicalConceptIds() {
        SemanticAmbiguityRegistry registry =
                new SemanticAmbiguityRegistry(catalog);

        assertThat(registry.version())
                .isEqualTo("semantic-ambiguities-en-v1");
        assertThat(registry.isAmbiguous("Risk Assessment")).isTrue();
        assertThat(registry.require("risk-assessment").conceptIds())
                .containsExactlyInAnyOrder(
                        "finance_banking.lending_credit.credit_risk_assessment",
                        "earth_environmental_science.environmental_assessment.ecological_risk_assessment"
                );
        assertThat(registry.entries()).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void rejectsAmbiguityWithUnknownConcept() {
        SemanticAmbiguityDefinition definition =
                new SemanticAmbiguityDefinition(
                        "test-v1",
                        List.of(new SemanticAmbiguityDefinition.Entry(
                                "risk assessment",
                                List.of(
                                        "finance_banking.lending_credit.credit_risk_assessment",
                                        "missing.domain.concept"
                                ),
                                "test"
                        ))
                );

        assertThatThrownBy(() ->
                new SemanticAmbiguityRegistry(catalog, definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown semantic concept");
    }

    @Test
    void rejectsSingleMeaningEntryBecauseItIsNotAmbiguous() {
        SemanticAmbiguityDefinition definition =
                new SemanticAmbiguityDefinition(
                        "test-v1",
                        List.of(new SemanticAmbiguityDefinition.Entry(
                                "risk assessment",
                                List.of(
                                        "finance_banking.lending_credit.credit_risk_assessment"
                                ),
                                "test"
                        ))
                );

        assertThatThrownBy(() ->
                new SemanticAmbiguityRegistry(catalog, definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two concepts");
    }
}
