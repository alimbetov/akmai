package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdaptiveGraphReplayUtilityTest {

    private final AdaptiveGraphReplayUtility utility =
            new AdaptiveGraphReplayUtility();

    @Test
    void separatesAppendOnlyAndCompetitionUtility() {
        AdaptiveGraphReplayUtility.Weights weights =
                new AdaptiveGraphReplayUtility.Weights(
                        0.50,
                        0.20,
                        0.30
                );

        AdaptiveGraphReplayUtility.Evaluation evaluation =
                utility.evaluate(
                        quality(0.0, 0.0, 0.0),
                        quality(0.0, 0.0, 0.0),
                        quality(1.0, 0.20, 0.38685280723454163),
                        weights
                );

        assertThat(evaluation.appendOnlyUtilityDelta()).isZero();
        assertThat(evaluation.competitionUtilityDelta()).isPositive();
        assertThat(evaluation.overallUtilityDelta())
                .isEqualTo(evaluation.competitionUtilityDelta());
        assertThat(evaluation.safetyViolation()).isFalse();
    }

    @Test
    void marksRecallLossAsSafetyViolation() {
        AdaptiveGraphReplayUtility.Evaluation evaluation =
                utility.evaluate(
                        quality(1.0, 0.20, 0.38685280723454163),
                        quality(1.0, 0.20, 0.38685280723454163),
                        quality(0.0, 1.0 / 6.0, 0.3562071871080222),
                        new AdaptiveGraphReplayUtility.Weights(
                                0.50,
                                0.20,
                                0.30
                        )
                );

        assertThat(evaluation.competitionUtilityDelta()).isNegative();
        assertThat(evaluation.safetyViolation()).isTrue();
    }

    @Test
    void marksAppendOnlyRecallLossAsSafetyViolation() {
        AdaptiveGraphReplayUtility.Evaluation evaluation =
                utility.evaluate(
                        quality(1.0, 0.20, 0.38685280723454163),
                        quality(0.0, 1.0 / 6.0, 0.3562071871080222),
                        quality(1.0, 0.20, 0.38685280723454163),
                        new AdaptiveGraphReplayUtility.Weights(
                                0.50,
                                0.20,
                                0.30
                        )
                );

        assertThat(evaluation.safetyViolation()).isTrue();
    }

    @Test
    void normalizesUtilityWeights() {
        double first = utility.score(
                quality(1.0, 0.50, 0.25),
                new AdaptiveGraphReplayUtility.Weights(5.0, 2.0, 3.0)
        );
        double second = utility.score(
                quality(1.0, 0.50, 0.25),
                new AdaptiveGraphReplayUtility.Weights(0.50, 0.20, 0.30)
        );

        assertThat(first)
                .isCloseTo(
                        second,
                        org.assertj.core.data.Offset.offset(1.0e-12)
                );
    }

    private AdaptiveGraphReplayUtility.QualitySnapshot quality(
            double recallAt5,
            double reciprocalRank,
            double ndcgAt10
    ) {
        return new AdaptiveGraphReplayUtility.QualitySnapshot(
                recallAt5,
                reciprocalRank,
                ndcgAt10
        );
    }
}
