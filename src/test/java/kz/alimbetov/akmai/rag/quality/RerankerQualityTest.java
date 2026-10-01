package kz.alimbetov.akmai.rag.quality;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kz.alimbetov.akmai.rag.retrieval.Reranker;
import kz.alimbetov.akmai.rag.retrieval.RetrievalEvidence;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalObserver;
import kz.alimbetov.akmai.rag.retrieval.RetrievalProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalTestProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.junit.jupiter.api.Test;

class RerankerQualityTest {

    @Test
    void rerankingImprovesMrrAndNdcgWithoutReducingRecall() {
        RetrievalBenchmarkCase benchmark = new RetrievalBenchmarkCase(
                "ru-dose",
                "ru",
                "Какая дозировка препарата?",
                Set.of("dose", "route")
        );
        List<RetrievalHit> baseline = List.of(
                hit("noise", 0.95),
                hit("dose", 0.90),
                hit("route", 0.85)
        );
        RetrievalBenchmarkResult before = RetrievalBenchmarkEvaluator.evaluate(
                benchmark,
                ids(baseline)
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Reranker reranker = new Reranker(
                    (question, hit) -> switch (hit.chunkId()) {
                        case "dose" -> 0.98;
                        case "route" -> 0.85;
                        default -> 0.10;
                    },
                    properties(),
                    new RetrievalObserver(new SimpleMeterRegistry()),
                    executor
            );
            RetrievalBenchmarkResult after = RetrievalBenchmarkEvaluator.evaluate(
                    benchmark,
                    ids(reranker.rerank(baseline, benchmark.question()))
            );

            assertThat(after.recallAt5()).isGreaterThanOrEqualTo(before.recallAt5());
            assertThat(after.reciprocalRank()).isGreaterThan(before.reciprocalRank());
            assertThat(after.ndcgAt10()).isGreaterThan(before.ndcgAt10());
        } finally {
            executor.shutdownNow();
        }
    }

    private List<String> ids(List<RetrievalHit> hits) {
        return hits.stream().map(RetrievalHit::chunkId).toList();
    }

    private RetrievalProperties properties() {
        RetrievalProperties defaults = RetrievalTestProperties.defaults();
        return new RetrievalProperties(
                defaults.parallelism(), defaults.queueCapacity(),
                defaults.vectorTopK(), defaults.vectorSimilarityThreshold(),
                defaults.lexicalLimit(), defaults.identifierLimit(),
                defaults.referenceLimit(), defaults.rrfK(),
                defaults.expansionSeeds(), defaults.expansionRadius(),
                defaults.expansionMax(), defaults.contextMaxTokens(),
                defaults.contextMaxChunks(), defaults.contextMaxChunksPerDocument(),
                true, 20, Duration.ofSeconds(1), 0.15
        );
    }

    private RetrievalHit hit(String id, double fusedScore) {
        return new RetrievalHit(
                RetrievalType.VECTOR,
                "doc",
                id,
                id,
                Map.of(),
                List.of(new RetrievalEvidence(RetrievalType.VECTOR, 1, fusedScore)),
                fusedScore
        );
    }
}
