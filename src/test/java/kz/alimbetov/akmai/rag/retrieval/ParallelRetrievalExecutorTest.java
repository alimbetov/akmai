package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalStep;
import org.junit.jupiter.api.Test;

class ParallelRetrievalExecutorTest {

    private static final Set<Long> ACCESS = Set.of(1L);

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
                    () -> subject.execute(plan(), ACCESS)
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
    void preservesTypedRoutingIdentityWhenAddingQueryChunkMetadata() {
        ExecutorService executor = Executors.newFixedThreadPool(1);
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
                    return List.of(new RetrievalHit(
                            RetrievalType.VECTOR,
                            7L,
                            "doc",
                            3L,
                            "chunk",
                            "text",
                            Map.of("generation", 999L)
                    ));
                }
            };
            ParallelRetrievalExecutor subject = subject(
                    List.of(vector),
                    executor
            );
            RetrievalPlan plan = new RetrievalPlan(List.of(
                    new RetrievalStep(
                            "v",
                            query(),
                            RetrievalType.VECTOR,
                            List.of()
                    )
            ));

            RetrievalHit hit = subject.execute(
                    plan,
                    Set.of(7L, 8L)
            ).getFirst();

            assertThat(hit.accessLevel()).isEqualTo(7L);
            assertThat(hit.generation()).isEqualTo(3L);
            assertThat(hit.metadata())
                    .containsEntry("queryChunkId", query().id());
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

            RetrievalExecutionResult result = subject.executeDetailed(plan(), ACCESS);

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

            assertThatThrownBy(() -> subject.execute(cycle, ACCESS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cycle");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void emptyIdentifierScopeFailsClosedWithoutGlobalSemanticFallback() {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        AtomicBoolean semanticRan = new AtomicBoolean(false);
        try {
            RetrievalStrategy identifier = new RetrievalStrategy() {
                @Override
                public RetrievalType type() {
                    return RetrievalType.IDENTIFIER;
                }

                @Override
                public List<RetrievalHit> retrieve(
                        QueryChunk queryChunk,
                        RetrievalContext context
                ) {
                    return List.of();
                }
            };
            RetrievalStrategy vector = markingStrategy(
                    RetrievalType.VECTOR,
                    semanticRan
            );
            RetrievalStrategy lexical = markingStrategy(
                    RetrievalType.LEXICAL,
                    semanticRan
            );
            ParallelRetrievalExecutor subject = subject(
                    List.of(identifier, vector, lexical),
                    executor
            );
            QueryChunk query = query();
            RetrievalPlan scoped = new RetrievalPlan(List.of(
                    new RetrievalStep(
                            "id", query, RetrievalType.IDENTIFIER, List.of()
                    ),
                    new RetrievalStep(
                            "v", query, RetrievalType.VECTOR, List.of("id")
                    ),
                    new RetrievalStep(
                            "l", query, RetrievalType.LEXICAL, List.of("id")
                    )
            ));

            RetrievalExecutionResult result = subject.executeDetailed(scoped, ACCESS);

            assertThat(result.hits()).isEmpty();
            assertThat(semanticRan).isFalse();
            assertThat(result.outcomes().get("id").status())
                    .isEqualTo(RetrievalOutcomeStatus.EMPTY);
            assertThat(result.outcomes().get("v").status())
                    .isEqualTo(RetrievalOutcomeStatus.SKIPPED_DEPENDENCY);
            assertThat(result.outcomes().get("l").status())
                    .isEqualTo(RetrievalOutcomeStatus.SKIPPED_DEPENDENCY);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void identifierBackendFailureIsCriticalAndCannotBecomeZeroHitScope() {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        AtomicBoolean semanticRan = new AtomicBoolean(false);
        try {
            RetrievalStrategy identifier = new RetrievalStrategy() {
                @Override
                public RetrievalType type() {
                    return RetrievalType.IDENTIFIER;
                }

                @Override
                public List<RetrievalHit> retrieve(
                        QueryChunk queryChunk,
                        RetrievalContext context
                ) {
                    throw new IllegalStateException("identifier unavailable");
                }
            };
            ParallelRetrievalExecutor subject = subject(
                    List.of(
                            identifier,
                            markingStrategy(RetrievalType.VECTOR, semanticRan),
                            markingStrategy(RetrievalType.LEXICAL, semanticRan)
                    ),
                    executor
            );
            QueryChunk query = query();
            RetrievalPlan scoped = new RetrievalPlan(List.of(
                    new RetrievalStep(
                            "id", query, RetrievalType.IDENTIFIER, List.of()
                    ),
                    new RetrievalStep(
                            "v", query, RetrievalType.VECTOR, List.of("id")
                    ),
                    new RetrievalStep(
                            "l", query, RetrievalType.LEXICAL, List.of("id")
                    )
            ));

            RetrievalExecutionResult result = subject.executeDetailed(scoped, ACCESS);

            assertThat(result.criticalFailure()).isTrue();
            assertThat(semanticRan).isFalse();
            assertThat(result.outcomes().get("id").status())
                    .isEqualTo(RetrievalOutcomeStatus.FAILED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void perStrategyDeadlineProducesTypedTimeoutPromptly() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
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
                        Thread.sleep(5_000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return List.of();
                }
            };
            ParallelRetrievalExecutor subject = new ParallelRetrievalExecutor(
                    List.of(vector),
                    executor,
                    new RetrievalObserver(new SimpleMeterRegistry()),
                    properties(Duration.ofMillis(200), Duration.ofMillis(25))
            );
            RetrievalPlan plan = new RetrievalPlan(List.of(
                    new RetrievalStep(
                            "v", query(), RetrievalType.VECTOR, List.of()
                    )
            ));

            long started = System.nanoTime();
            RetrievalExecutionResult result = subject.executeDetailed(plan, ACCESS);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(result.outcomes().get("v").status())
                    .isEqualTo(RetrievalOutcomeStatus.TIMED_OUT);
            assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        } finally {
            executor.shutdownNow();
        }
    }


    @Test
    void overallRequestDeadlineBoundsBackendThatIgnoresInterrupt() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
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
                    while (release.getCount() > 0) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException ignored) {
                            // Deliberately ignore cancellation to emulate
                            // a non-cooperative backend.
                        }
                    }
                    return List.of();
                }
            };
            ParallelRetrievalExecutor subject = new ParallelRetrievalExecutor(
                    List.of(vector),
                    executor,
                    new RetrievalObserver(new SimpleMeterRegistry()),
                    properties(Duration.ofMillis(40), Duration.ofSeconds(5))
            );
            RetrievalPlan plan = new RetrievalPlan(List.of(
                    new RetrievalStep(
                            "v", query(), RetrievalType.VECTOR, List.of()
                    )
            ));

            long started = System.nanoTime();
            RetrievalExecutionResult result = subject.executeDetailed(plan, ACCESS);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(result.outcomes().get("v").status())
                    .isEqualTo(RetrievalOutcomeStatus.TIMED_OUT);
            assertThat(elapsed).isLessThan(Duration.ofMillis(500));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void saturatedRetrievalExecutorRejectsWithoutCallerExecution() throws Exception {
        java.util.concurrent.ThreadPoolExecutor executor =
                new java.util.concurrent.ThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(1),
                        new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
                );
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean strategyRan = new AtomicBoolean(false);
        try {
            executor.execute(() -> {
                workerStarted.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });

            ParallelRetrievalExecutor subject = new ParallelRetrievalExecutor(
                    List.of(markingStrategy(
                            RetrievalType.VECTOR,
                            strategyRan
                    )),
                    executor,
                    new RetrievalObserver(new SimpleMeterRegistry()),
                    RetrievalTestProperties.defaults()
            );
            RetrievalPlan plan = new RetrievalPlan(List.of(
                    new RetrievalStep(
                            "v", query(), RetrievalType.VECTOR, List.of()
                    )
            ));

            long started = System.nanoTime();
            RetrievalExecutionResult result = subject.executeDetailed(plan, ACCESS);

            assertThat(result.outcomes().get("v").status())
                    .isEqualTo(RetrievalOutcomeStatus.REJECTED);
            assertThat(strategyRan).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofMillis(500));
        } finally {
            release.countDown();
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

    private RetrievalStrategy markingStrategy(
            RetrievalType type,
            AtomicBoolean ran
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
                ran.set(true);
                return List.of(hit(type, "unexpected"));
            }
        };
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
