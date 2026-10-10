package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticCorpusNearDuplicateAuditorTest {

    @Test
    void flagsLexicallyCloseConceptsForReviewWithoutMergingThem() {
        List<SemanticConcept> concepts = List.of(
                new SemanticConcept(
                        "construction.facilities.facility_maintenance_plan",
                        "construction",
                        "facilities",
                        "facility maintenance plan"
                ),
                new SemanticConcept(
                        "construction.facilities.preventive_facility_maintenance",
                        "construction",
                        "facilities",
                        "preventive facility maintenance"
                ),
                new SemanticConcept(
                        "finance.risk.credit_risk_assessment",
                        "finance",
                        "risk",
                        "credit risk assessment"
                )
        );

        assertThat(SemanticCorpusNearDuplicateAuditor.reviewCandidates(concepts))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.leftPhrase())
                            .isEqualTo("facility maintenance plan");
                    assertThat(candidate.rightPhrase())
                            .isEqualTo("preventive facility maintenance");
                    assertThat(candidate.sameDomain()).isTrue();
                    assertThat(candidate.sameSubdomain()).isTrue();
                    assertThat(candidate.tokenOverlap()).isGreaterThan(0.66d);
                });
    }

    @Test
    void ignoresPairsThatOnlyShareGenericSingleToken() {
        List<SemanticConcept> concepts = List.of(
                new SemanticConcept(
                        "finance.risk.credit_risk_assessment",
                        "finance",
                        "risk",
                        "credit risk assessment"
                ),
                new SemanticConcept(
                        "insurance.risk.catastrophe_risk_model",
                        "insurance",
                        "risk",
                        "catastrophe risk model"
                )
        );

        assertThat(SemanticCorpusNearDuplicateAuditor.reviewCandidates(concepts))
                .isEmpty();
    }

    @Test
    void currentEnglishCorpusProducesOnlyReviewCandidatesNotAutomaticFailures() {
        EnglishSemanticConceptCatalog catalog =
                new EnglishSemanticConceptCatalog(new SemanticDomainCatalog());

        List<SemanticCorpusNearDuplicateAuditor.ReviewCandidate> candidates =
                SemanticCorpusNearDuplicateAuditor.reviewCandidates(
                        catalog.concepts()
                );

        assertThat(candidates)
                .allSatisfy(candidate -> {
                    assertThat(candidate.leftConceptId())
                            .isNotEqualTo(candidate.rightConceptId());
                    assertThat(candidate.tokenOverlap()).isBetween(0.66d, 1.0d);
                });
    }
}
