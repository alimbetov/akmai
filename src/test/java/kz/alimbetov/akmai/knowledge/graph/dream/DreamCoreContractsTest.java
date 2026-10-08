package kz.alimbetov.akmai.knowledge.graph.dream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;
import org.junit.jupiter.api.Test;

class DreamCoreContractsTest {

    @Test
    void canonicalPairIsOrderIndependentAndAclSafe() {
        ChunkGraphNode first = new ChunkGraphNode(1, "a", 1, "c1");
        ChunkGraphNode second = new ChunkGraphNode(1, "b", 1, "c2");

        assertThat(DreamPair.of(first, second))
                .isEqualTo(DreamPair.of(second, first));
        assertThat(DreamPair.of(first, second).first()).isEqualTo(first);

        ChunkGraphNode crossAcl = new ChunkGraphNode(2, "b", 1, "c2");
        assertThatThrownBy(() -> DreamPair.of(first, crossAcl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cross-ACL");
    }

    @Test
    void policyFingerprintIsStableAndChangesForMaterialInput() {
        DreamPolicyFingerprint.PolicyMaterial base = material(32);
        DreamPolicyFingerprint.PolicyMaterial changed = material(48);

        String first = DreamPolicyFingerprint.sha256(base);
        String second = DreamPolicyFingerprint.sha256(base);
        String different = DreamPolicyFingerprint.sha256(changed);

        assertThat(first).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(first).isEqualTo(second);
        assertThat(different).isNotEqualTo(first);
    }

    @Test
    void confidenceRequiresReciprocalNeighbourhoodAndRewardsBetterRank() {
        DreamConfidenceCalculator calculator = new DreamConfidenceCalculator();

        double nonMutual = calculator.calculate(
                0.97, 0.97, 1, 1, 32, false
        );
        double rankOne = calculator.calculate(
                0.97, 0.97, 1, 1, 32, true
        );
        double rankTen = calculator.calculate(
                0.97, 0.97, 10, 10, 32, true
        );

        assertThat(nonMutual).isZero();
        assertThat(rankOne).isGreaterThan(rankTen);
        assertThat(rankOne).isBetween(0.0, 1.0);
    }

    @Test
    void budgetReservesBeforeWorkAndReportsExhaustion() {
        DreamBudget budget = new DreamBudget(
                1,
                2,
                1,
                1,
                Duration.ofMinutes(1)
        );

        budget.acquireSource();
        budget.acquireForwardAnn();
        budget.acquireReverseAnn();
        budget.addDbRows(1);

        assertThatThrownBy(budget::acquireSource)
                .isInstanceOf(DreamBudget.BudgetExhaustedException.class)
                .satisfies(error -> assertThat(
                        ((DreamBudget.BudgetExhaustedException) error).reason()
                ).isEqualTo(DreamBudget.StopReason.MAX_SOURCES));
    }

    private DreamPolicyFingerprint.PolicyMaterial material(int topK) {
        return new DreamPolicyFingerprint.PolicyMaterial(
                "profile-v1",
                1024,
                "COSINE_DISTANCE",
                true,
                topK,
                0.86,
                0.94,
                0.90,
                0.86,
                DreamPolicyFingerprint.CONFIDENCE_FORMULA_V1,
                DreamPolicyFingerprint.NORMALIZATION_COSINE_V1,
                DreamPolicyFingerprint.ALGORITHM_V1
        );
    }
}
