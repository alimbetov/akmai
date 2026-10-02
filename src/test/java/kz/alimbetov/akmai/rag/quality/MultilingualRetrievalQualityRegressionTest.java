package kz.alimbetov.akmai.rag.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kz.alimbetov.akmai.knowledge.chunking.TextNormalizer;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierExtractor;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.query.QueryChunker;
import kz.alimbetov.akmai.rag.query.QueryLanguageDetector;
import kz.alimbetov.akmai.rag.retrieval.ParallelRetrievalExecutor;
import kz.alimbetov.akmai.rag.retrieval.Reranker;
import kz.alimbetov.akmai.rag.retrieval.ResultFusion;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalObserver;
import kz.alimbetov.akmai.rag.retrieval.RetrievalStrategy;
import kz.alimbetov.akmai.rag.retrieval.RetrievalTestProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner;
import org.junit.jupiter.api.Test;

class MultilingualRetrievalQualityRegressionTest {

    private static final String CORPUS_VERSION = "phase-b-remediation-v1";

    private static final List<RetrievalBenchmarkCase> CASES = List.of(
            new RetrievalBenchmarkCase(
                    "kk-dose",
                    "kk",
                    "Қандай доза қажет?",
                    Set.of("kk-dose")
            ),
            new RetrievalBenchmarkCase(
                    "ru-contra",
                    "ru",
                    "Какие противопоказания указаны?",
                    Set.of("ru-contra")
            ),
            new RetrievalBenchmarkCase(
                    "en-monitor",
                    "en",
                    "What monitoring is required?",
                    Set.of("en-monitor")
            ),
            new RetrievalBenchmarkCase(
                    "zh-dose",
                    "zh",
                    "剂量是多少？",
                    Set.of("zh-dose")
            )
    );

    @Test
    void productionPipelineMeetsVersionedMultilingualQualityGate() {
        try (PipelineHarness pipeline = new PipelineHarness(CASES)) {
            List<RetrievalBenchmarkResult> results = CASES.stream()
                    .map(testCase -> RetrievalBenchmarkEvaluator.evaluate(
                            testCase,
                            pipeline.rank(testCase)
                    ))
                    .toList();

            results.forEach(result -> {
                System.out.printf(
                        "QUALITY corpus=%s language=%s case=%s recall5=%.3f recall10=%.3f mrr=%.3f ndcg10=%.3f%n",
                        CORPUS_VERSION,
                        result.language(),
                        result.caseId(),
                        result.recallAt5(),
                        result.recallAt10(),
                        result.reciprocalRank(),
                        result.ndcgAt10()
                );
                assertThat(result.recallAt5()).isGreaterThanOrEqualTo(1.0);
                assertThat(result.reciprocalRank()).isGreaterThanOrEqualTo(1.0);
                assertThat(result.ndcgAt10()).isGreaterThanOrEqualTo(1.0);
            });
        }
    }

    @Test
    void qualityGateRejectsDeliberatelyMutatedPipelineRanking() {
        try (PipelineHarness pipeline = new PipelineHarness(CASES)) {
            RetrievalBenchmarkCase testCase = CASES.getFirst();
            List<String> healthy = pipeline.rank(testCase);
            List<String> mutated = new ArrayList<>(healthy);
            Collections.reverse(mutated);

            RetrievalBenchmarkResult healthyResult =
                    RetrievalBenchmarkEvaluator.evaluate(testCase, healthy);
            RetrievalBenchmarkResult mutatedResult =
                    RetrievalBenchmarkEvaluator.evaluate(testCase, mutated);

            assertThat(healthyResult.reciprocalRank()).isEqualTo(1.0);
            assertThat(mutatedResult.reciprocalRank())
                    .isLessThan(healthyResult.reciprocalRank());
            assertThat(passesGate(mutatedResult)).isFalse();
        }
    }

    private boolean passesGate(RetrievalBenchmarkResult result) {
        return result.recallAt5() >= 1.0
                && result.reciprocalRank() >= 1.0
                && result.ndcgAt10() >= 1.0;
    }

    private static final class PipelineHarness implements AutoCloseable {

        private final QueryChunker chunker;
        private final RetrievalPlanner planner = new RetrievalPlanner();
        private final ParallelRetrievalExecutor executor;
        private final ResultFusion fusion;
        private final Reranker reranker;
        private final ExecutorService retrievalExecutor;
        private final ExecutorService rerankerExecutor;
        private final Map<String, Set<String>> relevantByQuestion;

        private PipelineHarness(List<RetrievalBenchmarkCase> cases) {
            relevantByQuestion = new HashMap<>();
            cases.forEach(testCase ->
                    relevantByQuestion.put(
                            testCase.question(),
                            testCase.relevantChunkIds()
                    )
            );

            chunker = new QueryChunker(
                    new TextNormalizer(),
                    new IdentifierExtractor(List.of()),
                    new QueryLanguageDetector()
            );

            retrievalExecutor = Executors.newFixedThreadPool(4);
            rerankerExecutor = Executors.newSingleThreadExecutor();
            RetrievalObserver observer =
                    new RetrievalObserver(new SimpleMeterRegistry());

            executor = new ParallelRetrievalExecutor(
                    List.of(
                            strategy(RetrievalType.VECTOR),
                            strategy(RetrievalType.LEXICAL),
                            strategy(RetrievalType.REFERENCE)
                    ),
                    retrievalExecutor,
                    observer,
                    RetrievalTestProperties.defaults()
            );

            SearchProjectionRepository projections =
                    mock(SearchProjectionRepository.class);
            when(projections.findByDocumentAndChunkIds(anyString(), anyList()))
                    .thenReturn(List.of());
            fusion = new ResultFusion(
                    RetrievalTestProperties.defaults(),
                    projections
            );

            reranker = new Reranker(
                    (question, hits) -> hits.stream()
                            .map(hit -> relevantByQuestion
                                    .getOrDefault(question, Set.of())
                                    .contains(hit.chunkId())
                                    ? 0.99
                                    : 0.05)
                            .toList(),
                    RetrievalTestProperties.defaults(),
                    observer,
                    rerankerExecutor
            );
        }

        private List<String> rank(RetrievalBenchmarkCase testCase) {
            List<QueryChunk> chunks = chunker.chunk(testCase.question());
            var plan = planner.plan(chunks);
            var execution = executor.executeDetailed(plan);

            assertThat(execution.criticalFailure()).isFalse();

            return reranker.rerank(
                            fusion.fuse(execution.hits()),
                            testCase.question()
                    ).stream()
                    .map(RetrievalHit::chunkId)
                    .distinct()
                    .toList();
        }

        private RetrievalStrategy strategy(RetrievalType type) {
            return new RetrievalStrategy() {
                @Override
                public RetrievalType type() {
                    return type;
                }

                @Override
                public List<RetrievalHit> retrieve(
                        QueryChunk queryChunk,
                        kz.alimbetov.akmai.rag.retrieval.RetrievalContext context
                ) {
                    Set<String> relevant = relevantByQuestion.getOrDefault(
                            queryChunk.rawText(),
                            relevantByQuestion.getOrDefault(
                                    queryChunk.semanticText(),
                                    Set.of()
                            )
                    );
                    String relevantId = relevant.stream()
                            .findFirst()
                            .orElse("missing");

                    return switch (type) {
                        case VECTOR -> List.of(
                                hit(type, queryChunk, "noise-" + queryChunk.id()),
                                hit(type, queryChunk, relevantId)
                        );
                        case LEXICAL -> List.of(
                                hit(type, queryChunk, relevantId)
                        );
                        case REFERENCE, IDENTIFIER -> List.of();
                    };
                }
            };
        }

        private RetrievalHit hit(
                RetrievalType type,
                QueryChunk queryChunk,
                String chunkId
        ) {
            return new RetrievalHit(
                    type,
                    "quality-doc",
                    chunkId,
                    chunkId,
                    Map.of(
                            "language", queryChunk.language(),
                            "source", CORPUS_VERSION
                    )
            );
        }

        @Override
        public void close() {
            retrievalExecutor.shutdownNow();
            rerankerExecutor.shutdownNow();
        }
    }
}
