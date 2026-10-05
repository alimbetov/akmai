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
import java.util.concurrent.atomic.AtomicInteger;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalStep;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class ParallelRetrievalExecutorLifecycleEligibilityTest {

    private static final Set<Long> ACCESS = Set.of(1L);
    private static final List<RetrievalType> REQUIRED_LANES = List.of(
            RetrievalType.VECTOR,
            RetrievalType.LEXICAL,
            RetrievalType.CONCEPT,
            RetrievalType.IDENTIFIER,
            RetrievalType.REFERENCE
    );

    @Test
    void lifecycleFenceRemovesExpiredHitsFromEveryRetrievalLane() {
        for (RetrievalType type : REQUIRED_LANES) {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                AtomicInteger fenceCalls = new AtomicInteger();
                PublishedLifecycleEligibility denyAll = (hits, accessLevels) -> {
                    fenceCalls.incrementAndGet();
                    assertThat(accessLevels).isEqualTo(ACCESS);
                    assertThat(hits)
                            .extracting(RetrievalHit::type)
                            .containsExactly(type);
                    return List.of();
                };

                ParallelRetrievalExecutor subject = new ParallelRetrievalExecutor(
                        List.of(strategy(type, new AtomicBoolean())),
                        worker,
                        new RetrievalObserver(new SimpleMeterRegistry()),
                        properties(),
                        denyAll
                );
                RetrievalPlan plan = new RetrievalPlan(List.of(
                        new RetrievalStep(
                                type.name().toLowerCase(),
                                query(),
                                type,
                                List.of()
                        )
                ));

                RetrievalExecutionResult result =
                        subject.executeDetailed(plan, ACCESS);

                assertThat(result.hits()).isEmpty();
                assertThat(result.outcomes()
                        .get(type.name().toLowerCase())
                        .status())
                        .isEqualTo(RetrievalOutcomeStatus.EMPTY);
                assertThat(fenceCalls).hasValue(1);
            } finally {
                worker.shutdownNow();
            }
        }
    }

    @Test
    void expiredIdentifierCannotInfluenceDependentSemanticRetrieval() {
        ExecutorService worker = Executors.newFixedThreadPool(2);
        try {
            AtomicBoolean identifierRan = new AtomicBoolean();
            AtomicBoolean vectorRan = new AtomicBoolean();
            PublishedLifecycleEligibility denyIdentifier = (hits, accessLevels) ->
                    hits.stream()
                            .filter(hit -> hit.type() != RetrievalType.IDENTIFIER)
                            .toList();

            ParallelRetrievalExecutor subject = new ParallelRetrievalExecutor(
                    List.of(
                            strategy(RetrievalType.IDENTIFIER, identifierRan),
                            strategy(RetrievalType.VECTOR, vectorRan)
                    ),
                    worker,
                    new RetrievalObserver(new SimpleMeterRegistry()),
                    properties(),
                    denyIdentifier
            );
            RetrievalPlan plan = new RetrievalPlan(List.of(
                    new RetrievalStep(
                            "identifier",
                            query(),
                            RetrievalType.IDENTIFIER,
                            List.of()
                    ),
                    new RetrievalStep(
                            "vector",
                            query(),
                            RetrievalType.VECTOR,
                            List.of("identifier")
                    )
            ));

            RetrievalExecutionResult result =
                    subject.executeDetailed(plan, ACCESS);

            assertThat(identifierRan).isTrue();
            assertThat(vectorRan).isFalse();
            assertThat(result.hits()).isEmpty();
            assertThat(result.outcomes().get("identifier").status())
                    .isEqualTo(RetrievalOutcomeStatus.EMPTY);
            assertThat(result.outcomes().get("vector").status())
                    .isEqualTo(RetrievalOutcomeStatus.SKIPPED_DEPENDENCY);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void lifecycleFenceFailureFailsClosedInsteadOfReturningUnvalidatedHits() {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            PublishedLifecycleEligibility unavailable = (hits, accessLevels) -> {
                throw new DataAccessResourceFailureException(
                        "lifecycle store unavailable"
                );
            };
            ParallelRetrievalExecutor subject = new ParallelRetrievalExecutor(
                    List.of(strategy(RetrievalType.VECTOR, new AtomicBoolean())),
                    worker,
                    new RetrievalObserver(new SimpleMeterRegistry()),
                    properties(),
                    unavailable
            );
            RetrievalPlan plan = new RetrievalPlan(List.of(
                    new RetrievalStep(
                            "vector",
                            query(),
                            RetrievalType.VECTOR,
                            List.of()
                    )
            ));

            RetrievalExecutionResult result =
                    subject.executeDetailed(plan, ACCESS);

            assertThat(result.hits()).isEmpty();
            assertThat(result.outcomes().get("vector").status())
                    .isEqualTo(RetrievalOutcomeStatus.FAILED);
        } finally {
            worker.shutdownNow();
        }
    }

    private RetrievalStrategy strategy(
            RetrievalType type,
            AtomicBoolean invoked
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
                invoked.set(true);
                return List.of(hit(type));
            }
        };
    }

    private RetrievalHit hit(RetrievalType type) {
        return new RetrievalHit(
                type,
                1L,
                "doc-" + type.name().toLowerCase(),
                7L,
                "chunk-" + type.name().toLowerCase(),
                "text",
                Map.of("generation", 7L)
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

    private RetrievalProperties properties() {
        return new RetrievalProperties(
                2,
                16,
                10,
                0.1,
                10,
                10,
                10,
                60,
                3,
                1,
                3,
                4096,
                10,
                3,
                false,
                10,
                Duration.ofMillis(100),
                0.5
        );
    }
}
