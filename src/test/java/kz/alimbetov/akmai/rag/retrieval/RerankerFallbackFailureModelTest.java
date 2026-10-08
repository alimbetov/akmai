package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class RerankerFallbackFailureModelTest {

    @Test
    void emptyScorerOutputFallsBackToOriginalOrdering() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            List<RetrievalHit> original = List.of(
                    hit("first", 0.9),
                    hit("second", 0.8)
            );
            Reranker reranker = reranker(
                    (question, hits) -> List.of(),
                    executor,
                    Duration.ofSeconds(1)
            );

            assertThat(reranker.rerank(original, "question"))
                    .isEqualTo(original);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void scorerCardinalityMismatchFallsBackToOriginalOrdering() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            List<RetrievalHit> original = List.of(
                    hit("first", 0.9),
                    hit("second", 0.8)
            );
            Reranker reranker = reranker(
                    (question, hits) -> List.of(0.5),
                    executor,
                    Duration.ofSeconds(1)
            );

            assertThat(reranker.rerank(original, "question"))
                    .isEqualTo(original);
        } finally {
            executor.shutdownNow();
        }
    }

    private Reranker reranker(
            SemanticRerankScorer scorer,
            ExecutorService executor,
            Duration timeout
    ) {
        RetrievalProperties defaults = RetrievalTestProperties.defaults();
        RetrievalProperties properties = new RetrievalProperties(
                defaults.parallelism(),
                defaults.queueCapacity(),
                defaults.vectorTopK(),
                defaults.vectorSimilarityThreshold(),
                defaults.lexicalLimit(),
                defaults.identifierLimit(),
                defaults.referenceLimit(),
                defaults.rrfK(),
                defaults.expansionSeeds(),
                defaults.expansionRadius(),
                defaults.expansionMax(),
                defaults.contextMaxTokens(),
                defaults.contextMaxChunks(),
                defaults.contextMaxChunksPerDocument(),
                true,
                20,
                timeout,
                0.15
        );
        return new Reranker(
                scorer,
                properties,
                new RetrievalObserver(new SimpleMeterRegistry()),
                executor
        );
    }

    private RetrievalHit hit(String chunkId, double fusedScore) {
        return new RetrievalHit(
                RetrievalType.VECTOR,
                1L,
                "doc",
                1L,
                chunkId,
                chunkId,
                Map.of(),
                List.of(),
                fusedScore
        );
    }
}
