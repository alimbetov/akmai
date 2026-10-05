package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalStep;
import org.junit.jupiter.api.Test;

class ParallelRetrievalExecutorCancellationTest {

    @Test
    void strategyTimeoutInterruptsActualWorkerTask() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean returnedNormally = new AtomicBoolean(false);
        try {
            RetrievalStrategy vector = new RetrievalStrategy() {
                @Override
                public RetrievalType type() {
                    return RetrievalType.VECTOR;
                }

                @Override
                public List<RetrievalHit> retrieve(
                        QueryChunk queryChunk,
                        RetrievalContext context
                ) {
                    started.countDown();
                    try {
                        Thread.sleep(10_000);
                        returnedNormally.set(true);
                    } catch (InterruptedException exception) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "worker interrupted",
                                exception
                        );
                    }
                    return List.of();
                }
            };
            ParallelRetrievalExecutor subject = new ParallelRetrievalExecutor(
                    List.of(vector),
                    executor,
                    new RetrievalObserver(new SimpleMeterRegistry()),
                    properties(Duration.ofSeconds(2), Duration.ofMillis(50))
            );

            RetrievalExecutionResult result = subject.executeDetailed(
                    plan(),
                    Set.of(1L)
            );

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(result.outcomes().get("v").status())
                    .isEqualTo(RetrievalOutcomeStatus.TIMED_OUT);
            assertThat(interrupted.await(1, TimeUnit.SECONDS))
                    .as("timeout must interrupt the actual retrieval worker")
                    .isTrue();
            assertThat(returnedNormally).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void requestDeadlineInterruptsActualWorkerTask() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            RetrievalStrategy vector = new RetrievalStrategy() {
                @Override
                public RetrievalType type() {
                    return RetrievalType.VECTOR;
                }

                @Override
                public List<RetrievalHit> retrieve(
                        QueryChunk queryChunk,
                        RetrievalContext context
                ) {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException exception) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "worker interrupted",
                                exception
                        );
                    }
                    return List.of();
                }
            };
            ParallelRetrievalExecutor subject = new ParallelRetrievalExecutor(
                    List.of(vector),
                    executor,
                    new RetrievalObserver(new SimpleMeterRegistry()),
                    properties(Duration.ofMillis(50), Duration.ofSeconds(5))
            );

            RetrievalExecutionResult result = subject.executeDetailed(
                    plan(),
                    Set.of(1L)
            );

            assertThat(result.outcomes().get("v").status())
                    .isEqualTo(RetrievalOutcomeStatus.TIMED_OUT);
            assertThat(interrupted.await(1, TimeUnit.SECONDS))
                    .as("request deadline must interrupt the actual retrieval worker")
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void timedOutTaskReleasesSingleWorkerForNextRetrieval() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        try {
            RetrievalStrategy vector = new RetrievalStrategy() {
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
                            Thread.sleep(10_000);
                        } catch (InterruptedException exception) {
                            interrupted.countDown();
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(
                                    "worker interrupted",
                                    exception
                            );
                        }
                    }
                    return List.of();
                }
            };
            ParallelRetrievalExecutor subject = new ParallelRetrievalExecutor(
                    List.of(vector),
                    executor,
                    new RetrievalObserver(new SimpleMeterRegistry()),
                    properties(Duration.ofSeconds(1), Duration.ofMillis(150))
            );

            RetrievalExecutionResult first = subject.executeDetailed(
                    plan(),
                    Set.of(1L)
            );
            assertThat(first.outcomes().get("v").status())
                    .isEqualTo(RetrievalOutcomeStatus.TIMED_OUT);
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();

            RetrievalExecutionResult second = subject.executeDetailed(
                    plan(),
                    Set.of(1L)
            );

            assertThat(second.outcomes().get("v").status())
                    .as("cancelled work must not starve the only retrieval worker")
                    .isEqualTo(RetrievalOutcomeStatus.EMPTY);
            assertThat(invocations).hasValue(2);
        } finally {
            executor.shutdownNow();
        }
    }

    private RetrievalPlan plan() {
        return new RetrievalPlan(List.of(new RetrievalStep(
                "v",
                query(),
                RetrievalType.VECTOR,
                List.of()
        )));
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

    private QueryChunk query() {
        return new QueryChunk(
                "q1", 0, "query", "query", "query", "en", List.of()
        );
    }
}
