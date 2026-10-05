package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import kz.alimbetov.akmai.knowledge.semantic.SemanticConceptMatch;
import kz.alimbetov.akmai.knowledge.semantic.SemanticMatchMode;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalysis;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalyzer;
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
    void canonicalConceptOverlapBreaksCloseTieWithBoundedBoost() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            String conceptId =
                    "finance_banking.risk_capital.capital_adequacy_ratio";
            RetrievalHit baseline = new RetrievalHit(
                    RetrievalType.VECTOR,
                    "doc",
                    "baseline",
                    "baseline",
                    Map.of(
                            "semanticConcepts",
                            List.of("other.concept")
                    ),
                    List.of(),
                    0.8
            );
            RetrievalHit conceptMatch = new RetrievalHit(
                    RetrievalType.VECTOR,
                    "doc",
                    "concept",
                    "concept",
                    Map.of(
                            "semanticConcepts",
                            List.of(conceptId)
                    ),
                    List.of(),
                    0.8
            );

            SemanticQueryAnalyzer analyzer =
                    mock(SemanticQueryAnalyzer.class);
            when(analyzer.analyze("capital adequacy ratio"))
                    .thenReturn(new SemanticQueryAnalysis(
                            "unknown",
                            "en",
                            1.0,
                            List.of("finance_banking"),
                            List.of(new SemanticConceptMatch(
                                    conceptId,
                                    "finance_banking",
                                    "risk_capital",
                                    "capital adequacy ratio",
                                    3.0,
                                    SemanticMatchMode.EXACT
                            ))
                    ));

            Reranker reranker = reranker(
                    (question, hits) -> List.of(0.5, 0.5),
                    executor,
                    Duration.ofSeconds(1),
                    analyzer
            );

            List<RetrievalHit> result = reranker.rerank(
                    List.of(baseline, conceptMatch),
                    "capital adequacy ratio"
            );

            assertThat(result)
                    .extracting(RetrievalHit::chunkId)
                    .containsExactly("concept", "baseline");
            assertThat(result.getFirst().metadata())
                    .containsEntry("rerankConceptOverlap", 1);
            assertThat(
                    (double) result.getFirst()
                            .metadata()
                            .get("rerankConceptBoost")
            ).isCloseTo(0.02, within(1.0e-12));
            assertThat(result.get(1).metadata())
                    .containsEntry("rerankConceptOverlap", 0)
                    .containsEntry("rerankConceptBoost", 0.0);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void conceptBoostCannotOverrideAuthorityTier() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            String conceptId =
                    "finance_banking.risk_capital.capital_adequacy_ratio";
            RetrievalHit authority = new RetrievalHit(
                    RetrievalType.IDENTIFIER,
                    "doc",
                    "authority",
                    "authority",
                    Map.of("authorityTier", 0),
                    List.of(),
                    0.4
            );
            RetrievalHit conceptMatch = new RetrievalHit(
                    RetrievalType.VECTOR,
                    "doc",
                    "semantic",
                    "semantic",
                    Map.of(
                            "authorityTier", 2,
                            "semanticConcepts", List.of(conceptId)
                    ),
                    List.of(),
                    0.9
            );

            SemanticQueryAnalyzer analyzer =
                    mock(SemanticQueryAnalyzer.class);
            when(analyzer.analyze("capital adequacy ratio"))
                    .thenReturn(new SemanticQueryAnalysis(
                            "en",
                            "en",
                            1.0,
                            List.of("finance_banking"),
                            List.of(new SemanticConceptMatch(
                                    conceptId,
                                    "finance_banking",
                                    "risk_capital",
                                    "capital adequacy ratio",
                                    3.0,
                                    SemanticMatchMode.EXACT
                            ))
                    ));

            Reranker reranker = reranker(
                    (question, hits) -> List.of(0.01, 0.99),
                    executor,
                    Duration.ofSeconds(1),
                    analyzer
            );

            assertThat(reranker.rerank(
                    List.of(authority, conceptMatch),
                    "capital adequacy ratio"
            )).extracting(RetrievalHit::chunkId)
                    .containsExactly("authority", "semantic");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rerankingPreservesExplicitAclAndGenerationIdentity() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RetrievalHit first = new RetrievalHit(
                    RetrievalType.VECTOR,
                    7,
                    "doc",
                    3,
                    "first",
                    "first",
                    Map.of(),
                    List.of(),
                    0.9
            );
            RetrievalHit second = new RetrievalHit(
                    RetrievalType.VECTOR,
                    7,
                    "doc",
                    3,
                    "second",
                    "second",
                    Map.of(),
                    List.of(),
                    0.8
            );
            Reranker reranker = reranker(
                    (question, hits) -> List.of(0.8, 0.7),
                    executor,
                    Duration.ofSeconds(1)
            );

            List<RetrievalHit> result = reranker.rerank(
                    List.of(first, second),
                    "question"
            );

            assertThat(result)
                    .allSatisfy(hit -> {
                        assertThat(hit.accessLevel()).isEqualTo(7);
                        assertThat(hit.generation()).isEqualTo(3);
                        assertThat(hit.hasRoutingIdentity()).isTrue();
                    });
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
    void saturatedExecutorRejectsImmediatelyAndFallsBackWithoutCallerRuns() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy()
        );
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                workerStarted.countDown();
                await(release);
            });
            assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
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

            long startedAt = System.nanoTime();
            assertThat(reranker.rerank(original, "question")).isEqualTo(original);
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                    .isLessThan(Duration.ofMillis(200));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void exactAuthorityCannotBeDemotedBySemanticScore() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RetrievalHit exact = new RetrievalHit(
                    RetrievalType.IDENTIFIER,
                    "doc",
                    "exact",
                    "exact",
                    Map.of("authorityTier", 0),
                    List.of(new RetrievalEvidence(RetrievalType.IDENTIFIER, 1, 1.0)),
                    0.4
            );
            RetrievalHit semantic = new RetrievalHit(
                    RetrievalType.VECTOR,
                    "doc",
                    "semantic",
                    "semantic",
                    Map.of("authorityTier", 2),
                    List.of(new RetrievalEvidence(RetrievalType.VECTOR, 1, 1.0)),
                    0.9
            );
            Reranker reranker = reranker(
                    (question, hits) -> hits.stream()
                            .map(hit -> hit.chunkId().equals("semantic") ? 0.99 : 0.01)
                            .toList(),
                    executor,
                    Duration.ofSeconds(1)
            );

            assertThat(reranker.rerank(
                    List.of(exact, semantic),
                    "question"
            )).extracting(RetrievalHit::chunkId)
                    .containsExactly("exact", "semantic");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void nonFiniteScorerOutputFallsBackToOriginalOrder() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            List<RetrievalHit> original = List.of(
                    hit("first", 0.9, "e1"),
                    hit("second", 0.8, "e2")
            );
            Reranker reranker = reranker(
                    (question, hits) -> List.of(Double.NaN, 0.5),
                    executor,
                    Duration.ofSeconds(1)
            );

            assertThat(reranker.rerank(original, "question")).isEqualTo(original);
        } finally {
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

    private Reranker reranker(
            SemanticRerankScorer scorer,
            ExecutorService executor,
            Duration timeout,
            SemanticQueryAnalyzer semanticQueryAnalyzer
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
                executor,
                semanticQueryAnalyzer
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
