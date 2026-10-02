package kz.alimbetov.akmai.rag.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
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
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.knowledge.vector.PublishedVectorSearchRepository;
import kz.alimbetov.akmai.knowledge.vector.VectorSearchMatch;
import kz.alimbetov.akmai.rag.query.QueryChunker;
import kz.alimbetov.akmai.rag.query.QueryLanguageDetector;
import kz.alimbetov.akmai.rag.retrieval.LexicalRetrievalStrategy;
import kz.alimbetov.akmai.rag.retrieval.ParallelRetrievalExecutor;
import kz.alimbetov.akmai.rag.retrieval.ReferenceRetrievalStrategy;
import kz.alimbetov.akmai.rag.retrieval.Reranker;
import kz.alimbetov.akmai.rag.retrieval.ResultFusion;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalObserver;
import kz.alimbetov.akmai.rag.retrieval.RetrievalProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalTestProperties;
import kz.alimbetov.akmai.rag.retrieval.VectorRetrievalStrategy;
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
                            pipeline.rank(testCase, false)
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
    void qualityGateRejectsDeliberatelyMutatedProductionReranker() {
        try (PipelineHarness pipeline = new PipelineHarness(CASES)) {
            RetrievalBenchmarkCase testCase = CASES.getFirst();

            RetrievalBenchmarkResult healthy =
                    RetrievalBenchmarkEvaluator.evaluate(
                            testCase,
                            pipeline.rank(testCase, false)
                    );
            RetrievalBenchmarkResult mutated =
                    RetrievalBenchmarkEvaluator.evaluate(
                            testCase,
                            pipeline.rank(testCase, true)
                    );

            assertThat(healthy.reciprocalRank()).isEqualTo(1.0);
            assertThat(mutated.reciprocalRank())
                    .isLessThan(healthy.reciprocalRank());
            assertThat(passesGate(mutated)).isFalse();
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
        private final Reranker mutantReranker;
        private final ExecutorService retrievalExecutor;
        private final ExecutorService rerankerExecutor;
        private final Map<String, Set<String>> relevantByQuestion = new HashMap<>();
        private final Map<String, SearchProjection> projectionsByChunk = new HashMap<>();

        private PipelineHarness(List<RetrievalBenchmarkCase> cases) {
            RetrievalProperties properties = RetrievalTestProperties.defaults();
            cases.forEach(testCase -> {
                relevantByQuestion.put(
                        testCase.question(),
                        testCase.relevantChunkIds()
                );
                String relevant = testCase.relevantChunkIds().iterator().next();
                projectionsByChunk.put(
                        relevant,
                        projection(relevant, testCase.language())
                );
                projectionsByChunk.put(
                        "noise-" + testCase.id(),
                        projection("noise-" + testCase.id(), testCase.language())
                );
            });

            chunker = new QueryChunker(
                    new TextNormalizer(),
                    new IdentifierExtractor(List.of()),
                    new QueryLanguageDetector()
            );

            SearchProjectionRepository projectionRepository =
                    mock(SearchProjectionRepository.class);
            PublishedVectorSearchRepository vectorRepository =
                    mock(PublishedVectorSearchRepository.class);
            ReferenceGraphRepository referenceRepository =
                    mock(ReferenceGraphRepository.class);

            when(projectionRepository.findByDocumentAndChunkIds(
                    anyString(),
                    anyList()
            )).thenAnswer(invocation -> {
                List<String> ids = invocation.getArgument(1);
                return ids.stream()
                        .map(projectionsByChunk::get)
                        .filter(java.util.Objects::nonNull)
                        .toList();
            });

            when(projectionRepository.searchLexical(
                    anyString(),
                    anyString(),
                    anyList(),
                    anyInt()
            )).thenAnswer(invocation -> {
                String query = invocation.getArgument(0);
                String relevant = relevantId(query);
                SearchProjection projection = projectionsByChunk.get(relevant);
                return projection == null ? List.of() : List.of(projection);
            });

            when(vectorRepository.search(
                    anyString(),
                    anyList(),
                    anyInt(),
                    anyDouble()
            )).thenAnswer(invocation -> {
                String query = invocation.getArgument(0);
                String relevant = relevantId(query);
                String caseId = caseIdForQuestion(query);
                return List.of(
                        vectorMatch(
                                "noise-" + caseId,
                                caseLanguage(caseId),
                                0.95
                        ),
                        vectorMatch(relevant, caseLanguage(caseId), 0.80)
                );
            });

            when(referenceRepository.resolveSameDocumentTargets(
                    anyString(),
                    anyList(),
                    anyInt()
            )).thenReturn(List.of());

            retrievalExecutor = Executors.newFixedThreadPool(4);
            rerankerExecutor = Executors.newSingleThreadExecutor();
            RetrievalObserver observer =
                    new RetrievalObserver(new SimpleMeterRegistry());

            executor = new ParallelRetrievalExecutor(
                    List.of(
                            new VectorRetrievalStrategy(
                                    vectorRepository,
                                    properties
                            ),
                            new LexicalRetrievalStrategy(
                                    projectionRepository,
                                    properties
                            ),
                            new ReferenceRetrievalStrategy(
                                    projectionRepository,
                                    referenceRepository,
                                    properties
                            )
                    ),
                    retrievalExecutor,
                    observer,
                    properties
            );

            fusion = new ResultFusion(properties, projectionRepository);

            var scorer = (kz.alimbetov.akmai.rag.retrieval.SemanticRerankScorer)
                    (question, hits) -> hits.stream()
                            .map(hit -> relevantByQuestion
                                    .getOrDefault(question, Set.of())
                                    .contains(hit.chunkId())
                                    ? 0.99
                                    : 0.05)
                            .toList();

            reranker = new Reranker(
                    scorer,
                    properties,
                    observer,
                    rerankerExecutor
            );
            mutantReranker = new MutantReranker(
                    scorer,
                    properties,
                    observer,
                    rerankerExecutor
            );
        }

        private List<String> rank(
                RetrievalBenchmarkCase testCase,
                boolean mutateReranker
        ) {
            var execution = executor.executeDetailed(
                    planner.plan(chunker.chunk(testCase.question()))
            );
            assertThat(execution.criticalFailure()).isFalse();

            Reranker ranking = mutateReranker ? mutantReranker : reranker;
            return ranking.rerank(
                            fusion.fuse(execution.hits()),
                            testCase.question()
                    ).stream()
                    .map(RetrievalHit::chunkId)
                    .distinct()
                    .toList();
        }

        private String relevantId(String query) {
            return relevantByQuestion.getOrDefault(query, Set.of())
                    .stream()
                    .findFirst()
                    .orElseGet(() -> relevantByQuestion.entrySet().stream()
                            .filter(entry -> query.contains(entry.getKey())
                                    || entry.getKey().contains(query))
                            .flatMap(entry -> entry.getValue().stream())
                            .findFirst()
                            .orElse("missing"));
        }

        private String caseIdForQuestion(String query) {
            return CASES.stream()
                    .filter(testCase -> testCase.question().equals(query)
                            || testCase.question().contains(query)
                            || query.contains(testCase.question()))
                    .map(RetrievalBenchmarkCase::id)
                    .findFirst()
                    .orElse("unknown");
        }

        private String caseLanguage(String caseId) {
            return CASES.stream()
                    .filter(testCase -> testCase.id().equals(caseId))
                    .map(RetrievalBenchmarkCase::language)
                    .findFirst()
                    .orElse("en");
        }

        private VectorSearchMatch vectorMatch(
                String chunkId,
                String language,
                double score
        ) {
            return new VectorSearchMatch(
                    java.util.UUID.nameUUIDFromBytes(
                            chunkId.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    ).toString(),
                    "quality-doc",
                    1L,
                    chunkId,
                    chunkId,
                    Map.of(
                            "language", language,
                            "source", CORPUS_VERSION
                    ),
                    score
            );
        }

        private SearchProjection projection(
                String chunkId,
                String language
        ) {
            return new SearchProjection(
                    chunkId,
                    "quality-doc",
                    1L,
                    null,
                    Math.floorMod(chunkId.hashCode(), 1000),
                    chunkId,
                    chunkId,
                    language,
                    KnowledgeDomain.GENERAL,
                    "quality",
                    List.of(),
                    List.of(),
                    Map.of(
                            "language", language,
                            "source", CORPUS_VERSION
                    ),
                    2
            );
        }

        @Override
        public void close() {
            retrievalExecutor.shutdownNow();
            rerankerExecutor.shutdownNow();
        }
    }

    private static final class MutantReranker extends Reranker {

        private MutantReranker(
                kz.alimbetov.akmai.rag.retrieval.SemanticRerankScorer scorer,
                RetrievalProperties properties,
                RetrievalObserver observer,
                ExecutorService executor
        ) {
            super(scorer, properties, observer, executor);
        }

        @Override
        public List<RetrievalHit> rerank(
                List<RetrievalHit> hits,
                String question
        ) {
            List<RetrievalHit> ranked = new ArrayList<>(
                    super.rerank(hits, question)
            );
            Collections.reverse(ranked);
            return List.copyOf(ranked);
        }
    }
}
