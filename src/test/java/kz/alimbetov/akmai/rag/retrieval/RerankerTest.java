package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class RerankerTest {

    @Test
    void semanticScoreImprovesRankingAndPreservesRetrievalEvidence() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RetrievalHit noise = hit("noise", 0.9, "noise-evidence");
            RetrievalHit relevant = hit("relevant", 0.8, "relevant-evidence");
            Reranker reranker = reranker(
                    (question, hit) -> hit.chunkId().equals("relevant") ? 0.95 : 0.10,
                    executor,
                    Duration.ofSeconds(1)
            );

            List<RetrievalHit> result = reranker.rerank(
                    List.of(noise, relevant),
                    "relevant question"
            );

            assertThat(result).extracting(RetrievalHit::chunkId)
                    .containsExactly("relevant", "noise");
            assertThat(result.getFirst().fusedScore()).isEqualTo(0.8);
            assertThat(result.getFirst().evidence()).isEqualTo(relevant.evidence());
            assertThat(result.getFirst().metadata()).containsKeys(
                    "rerankSemanticScore",
                    "rerankScore"
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void scorerFailureFallsBackToOriginalRrfOrdering() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            List<RetrievalHit> original = List.of(
                    hit("first", 0.9, "e1"),
                    hit("second", 0.8, "e2")
            );
            Reranker reranker = reranker(
                    (question, hit) -> {
                        throw new IllegalStateException("scorer unavailable");
                    },
                    executor,
                    Duration.ofSeconds(1)
            );

            assertThat(reranker.rerank(original, "question")).isEqualTo(original);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void timeoutFallsBackToOriginalRrfOrdering() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            List<RetrievalHit> original = List.of(
                    hit("first", 0.9, "e1"),
                    hit("second", 0.8, "e2")
            );
            Reranker reranker = reranker(
                    (question, hit) -> {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        return 1.0;
                    },
                    executor,
                    Duration.ofMillis(20)
            );

            assertThat(reranker.rerank(original, "question")).isEqualTo(original);
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

    private RetrievalHit hit(String chunkId, double fusedScore, String evidenceId) {
        return new RetrievalHit(
                RetrievalType.VECTOR,
                "doc",
                chunkId,
                chunkId,
                Map.of(),
                List.of(new RetrievalEvidence(RetrievalType.VECTOR, 1, 0.5)),
                fusedScore
        );
    }
}
