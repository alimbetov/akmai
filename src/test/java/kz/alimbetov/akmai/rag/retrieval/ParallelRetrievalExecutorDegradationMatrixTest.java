package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalStep;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

class ParallelRetrievalExecutorDegradationMatrixTest {

    private static final Set<Long> ACCESS = Set.of(1L);

    static Stream<RetrievalType> executionLanes() {
        return Stream.of(
                RetrievalType.VECTOR,
                RetrievalType.LEXICAL,
                RetrievalType.CONCEPT,
                RetrievalType.IDENTIFIER,
                RetrievalType.REFERENCE,
                RetrievalType.HYDE_VECTOR
        );
    }

    @ParameterizedTest
    @MethodSource("executionLanes")
    void successfulLaneProducesSuccessWithoutDegradation(RetrievalType type) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ParallelRetrievalExecutor subject = subject(
                    List.of(strategy(type, () -> List.of(hit(type)))) ,
                    executor,
                    Duration.ofMillis(250),
                    Duration.ofMillis(100)
            );

            RetrievalExecutionResult result = subject.executeDetailed(
                    singleStep(type),
                    ACCESS
            );

            assertThat(result.outcomes().get("step").status())
                    .isEqualTo(RetrievalOutcomeStatus.SUCCESS);
            assertThat(result.outcomes().get("step").failureCategory()).isNull();
            assertThat(result.hits()).hasSize(1);
            assertThat(result.degraded()).isFalse();
            assertThat(result.criticalFailure()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @MethodSource("executionLanes")
    void emptyLaneProducesEmptyWithoutDegradation(RetrievalType type) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ParallelRetrievalExecutor subject = subject(
                    List.of(strategy(type, List::of)),
                    executor,
                    Duration.ofMillis(250),
                    Duration.ofMillis(100)
            );

            RetrievalExecutionResult result = subject.executeDetailed(
                    singleStep(type),
                    ACCESS
            );

            assertThat(result.outcomes().get("step").status())
                    .isEqualTo(RetrievalOutcomeStatus.EMPTY);
            assertThat(result.hits()).isEmpty();
            assertThat(result.degraded()).isFalse();
            assertThat(result.criticalFailure()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @MethodSource("executionLanes")
    void laneExceptionProducesTypedFailureAndDegradation(RetrievalType type) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ParallelRetrievalExecutor subject = subject(
                    List.of(strategy(type, () -> {
                        throw new IllegalStateException("backend unavailable");
                    })),
                    executor,
                    Duration.ofMillis(250),
                    Duration.ofMillis(100)
            );

            RetrievalExecutionResult result = subject.executeDetailed(
                    singleStep(type),
                    ACCESS
            );

            RetrievalStepOutcome outcome = result.outcomes().get("step");
            assertThat(outcome.status()).isEqualTo(RetrievalOutcomeStatus.FAILED);
            assertThat(outcome.failureCategory()).isEqualTo("IllegalStateException");
            assertThat(result.degraded()).isTrue();
            assertThat(result.criticalFailure())
                    .isEqualTo(type == RetrievalType.IDENTIFIER);
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @MethodSource("executionLanes")
    void laneTimeoutProducesTypedTimeoutAndDegradation(RetrievalType type) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ParallelRetrievalExecutor subject = subject(
                    List.of(strategy(type, () -> {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        return List.of();
                    })),
                    executor,
                    Duration.ofMillis(200),
                    Duration.ofMillis(25)
            );

            RetrievalExecutionResult result = subject.executeDetailed(
                    singleStep(type),
                    ACCESS
            );

            RetrievalStepOutcome outcome = result.outcomes().get("step");
            assertThat(outcome.status()).isEqualTo(RetrievalOutcomeStatus.TIMED_OUT);
            assertThat(outcome.failureCategory()).isNotBlank();
            assertThat(result.degraded()).isTrue();
            assertThat(result.criticalFailure())
                    .isEqualTo(type == RetrievalType.IDENTIFIER);
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @MethodSource("executionLanes")
    void missingStrategyProducesFailedOutcome(RetrievalType type) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ParallelRetrievalExecutor subject = subject(
                    List.of(),
                    executor,
                    Duration.ofMillis(250),
                    Duration.ofMillis(100)
            );

            RetrievalExecutionResult result = subject.executeDetailed(
                    singleStep(type),
                    ACCESS
            );

            RetrievalStepOutcome outcome = result.outcomes().get("step");
            assertThat(outcome.status()).isEqualTo(RetrievalOutcomeStatus.FAILED);
            assertThat(outcome.failureCategory()).isEqualTo("STRATEGY_MISSING");
            assertThat(result.degraded()).isTrue();
            assertThat(result.criticalFailure())
                    .isEqualTo(type == RetrievalType.IDENTIFIER);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void emptyIdentifierSkipsDependentSemanticLanesWithoutGlobalFallback() {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        AtomicBoolean dependentRan = new AtomicBoolean(false);
        try {
            RetrievalStrategy identifier = strategy(
                    RetrievalType.IDENTIFIER,
                    List::of
            );
            RetrievalStrategy vector = markingStrategy(
                    RetrievalType.VECTOR,
                    dependentRan
            );
            RetrievalStrategy lexical = markingStrategy(
                    RetrievalType.LEXICAL,
                    dependentRan
            );
            RetrievalStrategy concept = markingStrategy(
                    RetrievalType.CONCEPT,
                    dependentRan
            );
            ParallelRetrievalExecutor subject = subject(
                    List.of(identifier, vector, lexical, concept),
                    executor,
                    Duration.ofMillis(500),
                    Duration.ofMillis(150)
            );

            RetrievalExecutionResult result = subject.executeDetailed(
                    identifierScopedPlan(),
                    ACCESS
            );

            assertThat(result.outcomes().get("id").status())
                    .isEqualTo(RetrievalOutcomeStatus.EMPTY);
            assertThat(result.outcomes().get("v").status())
                    .isEqualTo(RetrievalOutcomeStatus.SKIPPED_DEPENDENCY);
            assertThat(result.outcomes().get("l").status())
                    .isEqualTo(RetrievalOutcomeStatus.SKIPPED_DEPENDENCY);
            assertThat(result.outcomes().get("c").status())
                    .isEqualTo(RetrievalOutcomeStatus.SKIPPED_DEPENDENCY);
            assertThat(result.outcomes().get("v").failureCategory())
                    .isEqualTo("IDENTIFIER_SCOPE_EMPTY");
            assertThat(dependentRan).isFalse();
            assertThat(result.degraded()).isTrue();
            assertThat(result.criticalFailure()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void identifierInfrastructureFailureIsCriticalAndSkipsDependents() {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        AtomicBoolean dependentRan = new AtomicBoolean(false);
        try {
            RetrievalStrategy identifier = strategy(
                    RetrievalType.IDENTIFIER,
                    () -> {
                        throw new IllegalStateException("identifier unavailable");
                    }
            );
            ParallelRetrievalExecutor subject = subject(
                    List.of(
                            identifier,
                            markingStrategy(RetrievalType.VECTOR, dependentRan),
                            markingStrategy(RetrievalType.LEXICAL, dependentRan),
                            markingStrategy(RetrievalType.CONCEPT, dependentRan)
                    ),
                    executor,
                    Duration.ofMillis(500),
                    Duration.ofMillis(150)
            );

            RetrievalExecutionResult result = subject.executeDetailed(
                    identifierScopedPlan(),
                    ACCESS
            );

            assertThat(result.outcomes().get("id").status())
                    .isEqualTo(RetrievalOutcomeStatus.FAILED);
            assertThat(result.outcomes().get("v").failureCategory())
                    .isEqualTo("IDENTIFIER_SCOPE_UNAVAILABLE");
            assertThat(result.outcomes().get("l").status())
                    .isEqualTo(RetrievalOutcomeStatus.SKIPPED_DEPENDENCY);
            assertThat(result.outcomes().get("c").status())
                    .isEqualTo(RetrievalOutcomeStatus.SKIPPED_DEPENDENCY);
            assertThat(dependentRan).isFalse();
            assertThat(result.degraded()).isTrue();
            assertThat(result.criticalFailure()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void onePrimarySemanticLaneFailureIsDegradedButNotCritical() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ParallelRetrievalExecutor subject = subject(
                    List.of(
                            strategy(RetrievalType.VECTOR, () -> {
                                throw new IllegalStateException("vector unavailable");
                            }),
                            strategy(
                                    RetrievalType.LEXICAL,
                                    () -> List.of(hit(RetrievalType.LEXICAL))
                            )
                    ),
                    executor,
                    Duration.ofMillis(500),
                    Duration.ofMillis(150)
            );

            RetrievalExecutionResult result = subject.executeDetailed(
                    primarySemanticPlan(),
                    ACCESS
            );

            assertThat(result.outcomes().get("v").status())
                    .isEqualTo(RetrievalOutcomeStatus.FAILED);
            assertThat(result.outcomes().get("l").status())
                    .isEqualTo(RetrievalOutcomeStatus.SUCCESS);
            assertThat(result.hits()).extracting(RetrievalHit::type)
                    .containsExactly(RetrievalType.LEXICAL);
            assertThat(result.degraded()).isTrue();
            assertThat(result.criticalFailure()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void bothPrimarySemanticInfrastructureLanesFailCritically() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ParallelRetrievalExecutor subject = subject(
                    List.of(
                            strategy(RetrievalType.VECTOR, () -> {
                                throw new IllegalStateException("vector unavailable");
                            }),
                            strategy(RetrievalType.LEXICAL, () -> {
                                throw new IllegalStateException("lexical unavailable");
                            })
                    ),
                    executor,
                    Duration.ofMillis(500),
                    Duration.ofMillis(150)
            );

            RetrievalExecutionResult result = subject.executeDetailed(
                    primarySemanticPlan(),
                    ACCESS
            );

            assertThat(result.outcomes().get("v").status())
                    .isEqualTo(RetrievalOutcomeStatus.FAILED);
            assertThat(result.outcomes().get("l").status())
                    .isEqualTo(RetrievalOutcomeStatus.FAILED);
            assertThat(result.hits()).isEmpty();
            assertThat(result.degraded()).isTrue();
            assertThat(result.criticalFailure()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void referenceWithoutSuccessfulDependencyHitsIsEmptyAndNotInvoked() {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        AtomicBoolean referenceRan = new AtomicBoolean(false);
        try {
            ParallelRetrievalExecutor subject = subject(
                    List.of(
                            strategy(RetrievalType.VECTOR, List::of),
                            strategy(RetrievalType.LEXICAL, List::of),
                            markingStrategy(RetrievalType.REFERENCE, referenceRan)
                    ),
                    executor,
                    Duration.ofMillis(500),
                    Duration.ofMillis(150)
            );
            QueryChunk query = query();
            RetrievalPlan plan = new RetrievalPlan(List.of(
                    new RetrievalStep("v", query, RetrievalType.VECTOR, List.of()),
                    new RetrievalStep("l", query, RetrievalType.LEXICAL, List.of()),
                    new RetrievalStep(
                            "r",
                            query,
                            RetrievalType.REFERENCE,
                            List.of("v", "l")
                    )
            ));

            RetrievalExecutionResult result = subject.executeDetailed(plan, ACCESS);

            assertThat(result.outcomes().get("v").status())
                    .isEqualTo(RetrievalOutcomeStatus.EMPTY);
            assertThat(result.outcomes().get("l").status())
                    .isEqualTo(RetrievalOutcomeStatus.EMPTY);
            assertThat(result.outcomes().get("r").status())
                    .isEqualTo(RetrievalOutcomeStatus.EMPTY);
            assertThat(referenceRan).isFalse();
            assertThat(result.degraded()).isFalse();
            assertThat(result.criticalFailure()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    private RetrievalPlan singleStep(RetrievalType type) {
        return new RetrievalPlan(List.of(
                new RetrievalStep("step", query(), type, List.of())
        ));
    }

    private RetrievalPlan identifierScopedPlan() {
        QueryChunk query = query();
        return new RetrievalPlan(List.of(
                new RetrievalStep("id", query, RetrievalType.IDENTIFIER, List.of()),
                new RetrievalStep("v", query, RetrievalType.VECTOR, List.of("id")),
                new RetrievalStep("l", query, RetrievalType.LEXICAL, List.of("id")),
                new RetrievalStep("c", query, RetrievalType.CONCEPT, List.of("id"))
        ));
    }

    private RetrievalPlan primarySemanticPlan() {
        QueryChunk query = query();
        return new RetrievalPlan(List.of(
                new RetrievalStep("v", query, RetrievalType.VECTOR, List.of()),
                new RetrievalStep("l", query, RetrievalType.LEXICAL, List.of())
        ));
    }

    private ParallelRetrievalExecutor subject(
            List<RetrievalStrategy> strategies,
            ExecutorService executor,
            Duration requestTimeout,
            Duration strategyTimeout
    ) {
        return new ParallelRetrievalExecutor(
                strategies,
                executor,
                new RetrievalObserver(new SimpleMeterRegistry()),
                properties(requestTimeout, strategyTimeout)
        );
    }

    private RetrievalStrategy strategy(
            RetrievalType type,
            HitSupplier supplier
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
                return supplier.get();
            }
        };
    }

    private RetrievalStrategy markingStrategy(
            RetrievalType type,
            AtomicBoolean invoked
    ) {
        return strategy(type, () -> {
            invoked.set(true);
            return List.of(hit(type));
        });
    }

    private RetrievalHit hit(RetrievalType type) {
        return new RetrievalHit(
                type,
                1L,
                "doc",
                1L,
                "chunk-" + type.name().toLowerCase(),
                "text",
                Map.of("source", "test")
        );
    }

    private QueryChunk query() {
        return new QueryChunk(
                "q",
                0,
                "question",
                "question",
                "question",
                "en",
                List.of()
        );
    }

    private RetrievalProperties properties(
            Duration requestTimeout,
            Duration strategyTimeout
    ) {
        return new RetrievalProperties(
                4,
                64,
                10,
                0.5,
                10,
                10,
                10,
                60,
                3,
                1,
                10,
                4096,
                10,
                3,
                false,
                10,
                Duration.ofSeconds(1),
                0.5,
                requestTimeout,
                strategyTimeout,
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                3,
                512
        );
    }

    @FunctionalInterface
    private interface HitSupplier {
        List<RetrievalHit> get();
    }
}
