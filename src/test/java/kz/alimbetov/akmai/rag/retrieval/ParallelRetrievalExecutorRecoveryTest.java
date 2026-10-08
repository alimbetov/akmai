package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalStep;
import org.junit.jupiter.api.Test;

class ParallelRetrievalExecutorRecoveryTest {

    @Test
    void timedOutInterruptibleStrategyReleasesSingleWorkerForNextRequest()
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        try {
            RetrievalStrategy strategy = new RetrievalStrategy() {
                @Override
                public RetrievalType type() {
                    return RetrievalType.VECTOR;
                }

                @Override
                public List<RetrievalHit> retrieve(
                        QueryChunk queryChunk,
                        RetrievalContext context
                ) {
                    if (invocations.incrementAndGet() == 1) {
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException exception) {
                            interrupted.countDown();
                            Thread.currentThread().interrupt();
                            return List.of();
                        }
                    }
                    return List.of(new RetrievalHit(
                            RetrievalType.VECTOR,
                            1L,
                            "doc",
                            1L,
                            "chunk",
                            "evidence",
                            Map.of()
                    ));
                }
            };

            ParallelRetrievalExecutor subject = new ParallelRetrievalExecutor(
                    List.of(strategy),
                    executor,
                    new RetrievalObserver(new SimpleMeterRegistry()),
                    properties(
                            Duration.ofMillis(400),
                            Duration.ofMillis(50)
                    )
            );

            RetrievalExecutionResult first = subject.executeDetailed(
                    plan("first"),
                    Set.of(1L)
            );

            assertThat(first.outcomes().get("first").status())
                    .isEqualTo(RetrievalOutcomeStatus.TIMED_OUT);
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();

            RetrievalExecutionResult second = subject.executeDetailed(
                    plan("second"),
                    Set.of(1L)
            );

            assertThat(second.outcomes().get("second").status())
                    .isEqualTo(RetrievalOutcomeStatus.SUCCESS);
            assertThat(second.hits())
                    .extracting(RetrievalHit::chunkId)
                    .containsExactly("chunk");
            assertThat(invocations).hasValue(2);
        } finally {
            executor.shutdownNow();
        }
    }

    private RetrievalPlan plan(String stepId) {
        QueryChunk query = new QueryChunk(
                "q-" + stepId,
                0,
                "question",
                "question",
                "question",
                "en",
                List.of()
        );
        return new RetrievalPlan(List.of(
                new RetrievalStep(
                        stepId,
                        query,
                        RetrievalType.VECTOR,
                        List.of()
                )
        ));
    }

    private RetrievalProperties properties(
            Duration requestTimeout,
            Duration strategyTimeout
    ) {
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
                defaults.rerankerEnabled(),
                defaults.rerankerCandidates(),
                defaults.rerankerTimeout(),
                defaults.rerankerFusedWeight(),
                requestTimeout,
                strategyTimeout,
                defaults.answerTimeout(),
                defaults.embeddingHttpTimeout(),
                defaults.contextExpansionMaxChunks(),
                defaults.answerReservedTokens()
        );
    }
}
