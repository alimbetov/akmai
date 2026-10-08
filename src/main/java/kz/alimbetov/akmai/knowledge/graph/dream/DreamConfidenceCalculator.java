package kz.alimbetov.akmai.knowledge.graph.dream;

import org.springframework.stereotype.Component;

@Component
public class DreamConfidenceCalculator {

    public double calculate(
            double forwardSimilarity,
            double reverseSimilarity,
            int forwardRank,
            int reverseRank,
            int topK,
            boolean mutualKnn
    ) {
        bounded(forwardSimilarity);
        bounded(reverseSimilarity);
        if (forwardRank <= 0 || reverseRank <= 0 || topK < 2) {
            throw new IllegalArgumentException("Dream ranks/topK are invalid");
        }
        if (!mutualKnn) {
            return 0.0;
        }
        double similarity = Math.min(forwardSimilarity, reverseSimilarity);
        int worstRank = Math.max(forwardRank, reverseRank);
        double rankFactor = 1.0 - ((double) (worstRank - 1) / topK);
        rankFactor = Math.max(0.0, Math.min(1.0, rankFactor));
        return Math.max(
                0.0,
                Math.min(1.0, similarity * (0.85 + 0.15 * rankFactor))
        );
    }

    private void bounded(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("similarity must be in [0, 1]");
        }
    }
}
