package kz.alimbetov.akmai.rag.quality;

import java.util.List;

public final class RetrievalBenchmarkEvaluator {

    private RetrievalBenchmarkEvaluator() {
    }

    public static RetrievalBenchmarkResult evaluate(
            RetrievalBenchmarkCase benchmarkCase,
            List<String> rankedChunkIds
    ) {
        return new RetrievalBenchmarkResult(
                benchmarkCase.id(),
                benchmarkCase.language(),
                RetrievalQualityMetrics.recallAtK(
                        rankedChunkIds, benchmarkCase.relevantChunkIds(), 5
                ),
                RetrievalQualityMetrics.recallAtK(
                        rankedChunkIds, benchmarkCase.relevantChunkIds(), 10
                ),
                RetrievalQualityMetrics.reciprocalRank(
                        rankedChunkIds, benchmarkCase.relevantChunkIds()
                ),
                RetrievalQualityMetrics.ndcgAtK(
                        rankedChunkIds, benchmarkCase.relevantChunkIds(), 10
                )
        );
    }
}
