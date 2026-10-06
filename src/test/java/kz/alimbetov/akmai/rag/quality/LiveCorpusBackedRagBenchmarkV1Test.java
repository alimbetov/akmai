package kz.alimbetov.akmai.rag.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.service.KnowledgeIngestionPort;
import kz.alimbetov.akmai.rag.query.QueryChunker;
import kz.alimbetov.akmai.rag.retrieval.ParallelRetrievalExecutor;
import kz.alimbetov.akmai.rag.retrieval.Reranker;
import kz.alimbetov.akmai.rag.retrieval.ResultFusion;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties = {
        "akmai.security.enabled=false",
        "akmai.reconciliation.enabled=false",
        "akmai.reembedding.auto-migrate=false",
        "akmai.adaptive-graph.learning-enabled=false",
        "akmai.adaptive-graph.expansion-enabled=false",
        "akmai.retrieval.adaptive-planner.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "AKMAI_LIVE_QUALITY", matches = "true")
class LiveCorpusBackedRagBenchmarkV1Test {

    private static final String BENCHMARK_VERSION = "rag-benchmark-v1";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres")
            )
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "spring.ai.ollama.base-url",
                () -> required("AKMAI_LIVE_OLLAMA_BASE_URL")
        );
    }

    @Autowired
    KnowledgeIngestionPort ingestion;
    @Autowired
    QueryChunker queryChunker;
    @Autowired
    RetrievalPlanner retrievalPlanner;
    @Autowired
    ParallelRetrievalExecutor retrievalExecutor;
    @Autowired
    ResultFusion resultFusion;
    @Autowired
    Reranker reranker;
    @Autowired
    ObjectMapper objectMapper;

    @BeforeAll
    void ingestCorpus() {
        for (RagBenchmarkV1Corpus.Case testCase : RagBenchmarkV1Corpus.smokeCases()) {
            ingestion.addText(
                    new AddKnowledgeRequest(
                            "benchmark-" + testCase.id(),
                            testCase.title(),
                            testCase.text(),
                            "benchmark://" + BENCHMARK_VERSION + "/" + testCase.id(),
                            testCase.language(),
                            testCase.domain(),
                            1L,
                            Map.of(
                                    "benchmarkVersion", BENCHMARK_VERSION,
                                    "benchmarkCase", testCase.id()
                            )
                    ),
                    "benchmark-ingest-" + testCase.id()
            );
        }
    }

    @Test
    void realProductionRetrievalPipelineProducesVersionedQualityReport()
            throws Exception {
        List<CaseResult> results = new ArrayList<>();
        for (RagBenchmarkV1Corpus.Case testCase : RagBenchmarkV1Corpus.smokeCases()) {
            var queryChunks = queryChunker.chunk(testCase.question());
            var plan = retrievalPlanner.plan(queryChunks);
            var execution = retrievalExecutor.executeDetailed(plan, Set.of(1L));
            assertThat(execution.criticalFailure()).isFalse();

            List<RetrievalHit> fused = resultFusion.fuse(execution.hits(), Set.of(1L));
            List<RetrievalHit> ranked = reranker.rerank(fused, testCase.question());
            List<String> rankedDocuments = ranked.stream()
                    .map(RetrievalHit::documentId)
                    .distinct()
                    .toList();
            String expected = "benchmark-" + testCase.id();
            int rank = rankOf(rankedDocuments, expected);
            results.add(new CaseResult(
                    testCase.id(),
                    testCase.language(),
                    testCase.domain().name(),
                    rank,
                    rank > 0 && rank <= 5 ? 1.0 : 0.0,
                    rank > 0 && rank <= 10 ? 1.0 : 0.0,
                    rank > 0 ? 1.0 / rank : 0.0,
                    rank > 0 ? 1.0 / log2(rank + 1.0) : 0.0
            ));
        }

        double recall5 = average(results, CaseResult::recall5);
        double recall10 = average(results, CaseResult::recall10);
        double mrr = average(results, CaseResult::mrr);
        double ndcg10 = average(results, CaseResult::ndcg10);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("benchmarkVersion", BENCHMARK_VERSION);
        report.put("mode", "LIVE_CORPUS_BACKED_PRODUCTION_RETRIEVAL");
        report.put("generatedAt", Instant.now().toString());
        report.put("caseCount", results.size());
        report.put("releaseCorpusQualified", false);
        report.put("note", "Checked-in corpus is a multilingual live smoke set; release qualification requires the separately curated >=300-query golden corpus.");
        report.put("recallAt5", recall5);
        report.put("recallAt10", recall10);
        report.put("mrr", mrr);
        report.put("ndcgAt10", ndcg10);
        report.put("cases", results);

        Path output = Path.of("target", "quality", "rag-benchmark-v1.json");
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);

        assertThat(recall10).isGreaterThanOrEqualTo(0.90);
        assertThat(mrr).isGreaterThanOrEqualTo(0.75);
        assertThat(ndcg10).isGreaterThanOrEqualTo(0.75);
    }

    private int rankOf(List<String> ranked, String expected) {
        for (int index = 0; index < ranked.size(); index++) {
            if (expected.equals(ranked.get(index))) {
                return index + 1;
            }
        }
        return 0;
    }

    private double average(
            List<CaseResult> results,
            java.util.function.ToDoubleFunction<CaseResult> metric
    ) {
        return results.stream().mapToDouble(metric).average().orElse(0.0);
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable " + name);
        }
        return value;
    }

    private record CaseResult(
            String caseId,
            String language,
            String domain,
            int rank,
            double recall5,
            double recall10,
            double mrr,
            double ndcg10
    ) {
    }
}
