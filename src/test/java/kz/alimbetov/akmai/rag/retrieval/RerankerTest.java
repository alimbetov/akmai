package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RerankerTest {

    @Test
    void semanticScoreImprovesRankingAndPreservesRetrievalEvidence() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RetrievalHit noise = hit("noise", 0.9, "noise-evidence");
            RetrievalHit relevant = hit("relevant", 0.8, "relevant-evidence");
            Reranker reranker = reranker(
                    (question, hits) -> hits.stream()
                            .map(hit -> hit.chunkId().equals("relevant") ? 0.95 : 0.10)
                            .toList(),
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
                    (question, hits) -> {
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
                    (question, hits) -> {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        return hits.stream().map(hit -> 1.0).toList();
                    },
                    executor,
                    Duration.ofMillis(20)
            );

            assertThat(reranker.rerank(original, "question")).isEqualTo(original);
        } finally {
            executor.shutdownNow();
        }
    }


    @Test
    void timeoutInterruptsScoringTaskInsteadOfLeavingWorkerOccupied() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            List<RetrievalHit> original = List.of(
                    hit("first", 0.9, "e1"),
                    hit("second", 0.8, "e2")
            );
            Reranker reranker = reranker(
                    (question, hits) -> {
                        try {
                            Thread.sleep(5_000);
                        } catch (InterruptedException exception) {
                            interrupted.countDown();
                            Thread.currentThread().interrupt();
                        }
                        return hits.stream().map(hit -> 1.0).toList();
                    },
                    executor,
                    Duration.ofMillis(20)
            );

            assertThat(reranker.rerank(original, "question")).isEqualTo(original);
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void saturatedExecutorRejectsImmediatelyAndFallsBackWithoutCallerRuns() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy()
        );
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                started.countDown();
                await(release);
            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> await(release));

            List<RetrievalHit> original = List.of(
                    hit("first", 0.9, "e1"),
                    hit("second", 0.8, "e2")
            );
            Reranker reranker = reranker(
                    (question, hits) -> {
                        throw new AssertionError("scorer must not run on caller thread");
                    },
                    executor,
                    Duration.ofSeconds(1)
            );

            long started = System.nanoTime();
            assertThat(reranker.rerank(original, "question")).isEqualTo(original);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofMillis(200));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
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
