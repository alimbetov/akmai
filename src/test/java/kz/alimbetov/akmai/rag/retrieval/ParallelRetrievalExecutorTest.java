package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalStep;
import org.junit.jupiter.api.Test;

class ParallelRetrievalExecutorTest {

    @Test
    void independentRootsRunConcurrentlyAndDependentStepSeesBothResults()
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);

            RetrievalStrategy vector = blocking(
                    RetrievalType.VECTOR, "vector", started, release
            );
            RetrievalStrategy lexical = blocking(
                    RetrievalType.LEXICAL, "lexical", started, release
            );
            RetrievalStrategy reference = new RetrievalStrategy() {
                @Override
                public RetrievalType type() {
                    return RetrievalType.REFERENCE;
                }

                @Override
                public List<RetrievalHit> retrieve(
                        QueryChunk queryChunk,
                        RetrievalContext context
                ) {
                    assertThat(context.dependencyHits())
                            .extracting(RetrievalHit::chunkId)
                            .containsExactlyInAnyOrder("vector", "lexical");
                    return List.of(hit(RetrievalType.REFERENCE, "reference"));
                }
            };

            ParallelRetrievalExecutor subject = subject(
                    List.of(vector, lexical, reference),
                    executor
            );

            var future = CompletableFuture.supplyAsync(
                    () -> subject.execute(plan())
            );

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            assertThat(future.get(2, TimeUnit.SECONDS))
                    .extracting(RetrievalHit::chunkId)
                    .containsExactly("vector", "lexical", "reference");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedPrimaryBranchIsPreservedAsTypedOutcome() {
        ExecutorService executor = Executors.newFixedThreadPool(3);
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
                    throw new IllegalStateException("vector unavailable");
                }
            };
            RetrievalStrategy lexical = immediate(
                    RetrievalType.LEXICAL, "lexical"
            );
            RetrievalStrategy reference = immediate(
                    RetrievalType.REFERENCE, "reference"
            );

            ParallelRetrievalExecutor subject = subject(
                    List.of(vector, lexical, reference),
                    executor
            );

            RetrievalExecutionResult result = subject.executeDetailed(plan());

            assertThat(result.criticalFailure()).isFalse();
            assertThat(result.degraded()).isTrue();
            assertThat(result.hits())
                    .extracting(RetrievalHit::chunkId)
                    .contains("lexical");
            assertThat(result.outcomes().get("v").status())
                    .isEqualTo(RetrievalOutcomeStatus.FAILED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsCyclicDependencyGraphInsteadOfRecursingForever() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ParallelRetrievalExecutor subject = subject(
                    List.<RetrievalStrategy>of(),
                    executor
            );
            QueryChunk query = query();
            RetrievalPlan cycle = new RetrievalPlan(List.of(
                    new RetrievalStep(
                            "a", query, RetrievalType.VECTOR, List.of("b")
                    ),
                    new RetrievalStep(
                            "b", query, RetrievalType.LEXICAL, List.of("a")
                    )
            ));

            assertThatThrownBy(() -> subject.execute(cycle))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cycle");
        } finally {
            executor.shutdownNow();
        }
    }

    private ParallelRetrievalExecutor subject(
            List<RetrievalStrategy> strategies,
            ExecutorService executor
    ) {
        return new ParallelRetrievalExecutor(
                strategies,
                executor,
                new RetrievalObserver(new SimpleMeterRegistry()),
                RetrievalTestProperties.defaults()
        );
    }

    private RetrievalPlan plan() {
        QueryChunk query = query();
        return new RetrievalPlan(List.of(
                new RetrievalStep("v", query, RetrievalType.VECTOR, List.of()),
                new RetrievalStep("l", query, RetrievalType.LEXICAL, List.of()),
                new RetrievalStep(
                        "r", query, RetrievalType.REFERENCE, List.of("v", "l")
                )
        ));
    }

    private RetrievalStrategy immediate(
            RetrievalType type,
            String chunkId
    ) {
        return new RetrievalStrategy() {
            @Override
            public RetrievalType type() {
                return type;
            }

            @Override
            public List<RetrievalHit> retrieve(
                    QueryChunk queryChunk,
                    RetrievalContext context
            ) {
                return List.of(hit(type, chunkId));
            }
        };
    }

    private RetrievalStrategy blocking(
            RetrievalType type,
            String chunkId,
            CountDownLatch started,
            CountDownLatch release
    ) {
        return new RetrievalStrategy() {
            @Override
            public RetrievalType type() {
                return type;
            }

            @Override
            public List<RetrievalHit> retrieve(
                    QueryChunk queryChunk,
                    RetrievalContext context
            ) {
                started.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("release timeout");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return List.of(hit(type, chunkId));
            }
        };
    }

    private QueryChunk query() {
        return new QueryChunk(
                "q1", 0, "query", "query", "query", "en", List.of()
        );
    }

    private RetrievalHit hit(RetrievalType type, String chunkId) {
        return new RetrievalHit(
                type, "doc", chunkId, chunkId, Map.of()
        );
    }
}
