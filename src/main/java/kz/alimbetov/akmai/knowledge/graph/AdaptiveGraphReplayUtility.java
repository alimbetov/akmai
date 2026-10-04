package kz.alimbetov.akmai.knowledge.graph;

/**
 * Versioned deterministic utility contract for adaptive graph replay.
 *
 * <p>The contract separates total graph lift from the incremental lift caused
 * by competitive admission. This prevents the calibrated competition
 * threshold from claiming utility that was already delivered by append-only
 * graph expansion.</p>
 */
public final class AdaptiveGraphReplayUtility {

    public static final String CONTRACT_VERSION =
            "adaptive-graph-ranking-utility-v1";

    public Evaluation evaluate(
            QualitySnapshot baseOnly,
            QualitySnapshot appendOnly,
            QualitySnapshot competitive,
            Weights weights
    ) {
        if (baseOnly == null
                || appendOnly == null
                || competitive == null
                || weights == null) {
            throw new IllegalArgumentException(
                    "replay utility inputs must not be null"
            );
        }

        double baseScore = score(baseOnly, weights);
        double appendScore = score(appendOnly, weights);
        double competitiveScore = score(competitive, weights);

        boolean safetyViolation =
                competitive.recallAt5() + 1.0e-12
                        < baseOnly.recallAt5();

        return new Evaluation(
                competitiveScore - baseScore,
                appendScore - baseScore,
                competitiveScore - appendScore,
                safetyViolation
        );
    }

    public double score(QualitySnapshot quality, Weights weights) {
        if (quality == null || weights == null) {
            throw new IllegalArgumentException(
                    "quality and weights must not be null"
            );
        }
        double totalWeight = weights.total();
        return (
                quality.recallAt5() * weights.recallAt5()
                        + quality.reciprocalRank()
                        * weights.reciprocalRank()
                        + quality.ndcgAt10() * weights.ndcgAt10()
        ) / totalWeight;
    }

    public record QualitySnapshot(
            double recallAt5,
            double reciprocalRank,
            double ndcgAt10
    ) {
        public QualitySnapshot {
            requireUnit("recallAt5", recallAt5);
            requireUnit("reciprocalRank", reciprocalRank);
            requireUnit("ndcgAt10", ndcgAt10);
        }
    }

    public record Weights(
            double recallAt5,
            double reciprocalRank,
            double ndcgAt10
    ) {
        public Weights {
            requireNonNegative("recallAt5", recallAt5);
            requireNonNegative("reciprocalRank", reciprocalRank);
            requireNonNegative("ndcgAt10", ndcgAt10);
            if (recallAt5 + reciprocalRank + ndcgAt10 <= 0.0) {
                throw new IllegalArgumentException(
                        "at least one replay utility weight must be positive"
                );
            }
        }

        public double total() {
            return recallAt5 + reciprocalRank + ndcgAt10;
        }
    }

    public record Evaluation(
            double overallUtilityDelta,
            double appendOnlyUtilityDelta,
            double competitionUtilityDelta,
            boolean safetyViolation
    ) {
    }

    private static void requireUnit(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and in [0, 1]"
            );
        }
    }

    private static void requireNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and non-negative"
            );
        }
    }
}
