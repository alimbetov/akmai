package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RerankerRecoveryTest {

    @Test
    void timedOutScorerDoesNotOccupySingleWorkerForNextHealthyRerank()
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            Reranker reranker = new Reranker(
                    (question, hits) -> {
                        if (calls.incrementAndGet() == 1) {
                            try {
                                Thread.sleep(5_000);
                            } catch (InterruptedException exception) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                            }
                            return List.of(1.0, 1.0);
                        }
                        return List.of(0.1, 0.9);
                    },
                    properties(Duration.ofMillis(50)),
                    new RetrievalObserver(new SimpleMeterRegistry()),
                    executor
            );
            List<RetrievalHit> original = List.of(
                    hit("first", 0.9),
                    hit("second", 0.8)
            );

            assertThat(reranker.rerank(original, "slow")).isEqualTo(original);
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            assertThat(reranker.rerank(original, "healthy"))
                    .extracting(RetrievalHit::chunkId)
                    .containsExactly("second", "first");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(1));
        } finally {
            executor.shutdownNow();
        }
    }

    private RetrievalProperties properties(Duration timeout) {
        RetrievalProperties defaults = RetrievalTestProperties.defaults();
        return new RetrievalProperties(
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
    }

    private RetrievalHit hit(String chunkId, double fusedScore) {
        return new RetrievalHit(
                RetrievalType.VECTOR,
                "doc",
                chunkId,
                chunkId,
                Map.of(),
                List.of(new RetrievalEvidence(RetrievalType.VECTOR, 1, 1.0)),
                fusedScore
        );
    }
}
