package kz.alimbetov.akmai.rag.quality;

public record RetrievalBenchmarkResult(
        String caseId,
        String language,
        double recallAt5,
        double recallAt10,
        double reciprocalRank,
        double ndcgAt10
) {
}
