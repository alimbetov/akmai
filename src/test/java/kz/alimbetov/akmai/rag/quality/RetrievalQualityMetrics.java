package kz.alimbetov.akmai.rag.quality;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RetrievalQualityMetrics {

    private RetrievalQualityMetrics() {
    }

    public static double recallAtK(
            List<String> rankedChunkIds,
            Set<String> relevantChunkIds,
            int k
    ) {
        if (relevantChunkIds.isEmpty()) {
            return 1.0;
        }
        Set<String> retrieved = new HashSet<>(
                rankedChunkIds.subList(0, Math.min(k, rankedChunkIds.size()))
        );
        long found = relevantChunkIds.stream().filter(retrieved::contains).count();
        return (double) found / relevantChunkIds.size();
    }

    public static double reciprocalRank(
            List<String> rankedChunkIds,
            Set<String> relevantChunkIds
    ) {
        for (int i = 0; i < rankedChunkIds.size(); i++) {
            if (relevantChunkIds.contains(rankedChunkIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    public static double ndcgAtK(
            List<String> rankedChunkIds,
            Set<String> relevantChunkIds,
            int k
    ) {
        double dcg = 0.0;
        int limit = Math.min(k, rankedChunkIds.size());
        for (int i = 0; i < limit; i++) {
            if (relevantChunkIds.contains(rankedChunkIds.get(i))) {
                dcg += 1.0 / log2(i + 2);
            }
        }

        double ideal = 0.0;
        int idealHits = Math.min(k, relevantChunkIds.size());
        for (int i = 0; i < idealHits; i++) {
            ideal += 1.0 / log2(i + 2);
        }
        return ideal == 0.0 ? 1.0 : dcg / ideal;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }
}
