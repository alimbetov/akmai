package kz.alimbetov.akmai.rag.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.service.KnowledgeIngestionPort;
import kz.alimbetov.akmai.rag.api.RagResponse;
import kz.alimbetov.akmai.rag.policy.CandidateRetrievalPolicyTestInstaller;
import kz.alimbetov.akmai.rag.query.QueryChunker;
import kz.alimbetov.akmai.rag.retrieval.ParallelRetrievalExecutor;
import kz.alimbetov.akmai.rag.retrieval.Reranker;
import kz.alimbetov.akmai.rag.retrieval.ResultFusion;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlanner;
import kz.alimbetov.akmai.rag.service.RagQuestionService;
import kz.alimbetov.akmai.rag.trace.RagExecutionObservationStore;
import kz.alimbetov.akmai.rag.trace.RagRuntimeAttribution;
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
        "akmai.retrieval.adaptive-planner.enabled=true",
        "akmai.self-optimizing.execution-observations-enabled=true"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "AKMAI_RELEASE_QUALITY", matches = "true")
class LiveReleaseRagBenchmarkV1Test {

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
    RagQuestionService questionService;
    @Autowired
    RagExecutionObservationStore observationStore;
    @Autowired
    RagRuntimeAttribution runtimeAttribution;
    @Autowired
    CandidateRetrievalPolicyTestInstaller candidatePolicyInstaller;
    @Autowired
    ObjectMapper objectMapper;

    private RagBenchmarkDataset dataset;

    @BeforeAll
    void ingestReleaseCorpus() throws Exception {
        Path root = Path.of(required("AKMAI_RAG_BENCHMARK_ROOT"));
        dataset = RagBenchmarkDatasetLoader.load(root, objectMapper);
        var validation = RagBenchmarkDatasetValidator.validateRelease(dataset);
        assertThat(validation.failures())
                .as("release benchmark corpus contract")
                .isEmpty();
        candidatePolicyInstaller.installIfConfigured();

        for (RagBenchmarkDataset.Document document : dataset.documents()) {
            ingestion.addText(
                    new AddKnowledgeRequest(
                            document.id(),
                            document.title(),
                            document.text(),
                            document.source(),
                            document.language(),
                            document.domain(),
                            document.accessLevel(),
                            document.metadata()
                    ),
                    "release-benchmark-ingest-" + dataset.corpusVersion()
                            + "-" + document.id()
            );
        }
    }

    @Test
    void fullProductionPipelineProducesReleaseQualifiedSnapshot()
            throws Exception {
        List<CaseResult> results = new ArrayList<>();
        for (RagBenchmarkDataset.Query query : dataset.queries()) {
            Set<Long> accessLevels = Set.of(1L);
            var queryChunks = queryChunker.chunk(query.question());
            var plan = retrievalPlanner.plan(queryChunks);
            var execution = retrievalExecutor.executeDetailed(plan, accessLevels);
            assertThat(execution.criticalFailure()).isFalse();

            List<RetrievalHit> fused = resultFusion.fuse(
                    execution.hits(),
                    accessLevels
            );
            List<RetrievalHit> ranked = reranker.rerank(
                    fused,
                    query.question()
            );
            RagResponse response = questionService.ask(
                    query.question(),
                    accessLevels
            );
            RagExecutionObservationStore.Observation observation =
                    observationStore.find(response.requestId()).orElse(null);

            results.add(evaluate(query, ranked, response, observation));
        }

        RagQualitySnapshot snapshot = snapshot(results);
        Path output = Path.of(
                "target",
                "quality",
                "rag-benchmark-v1-release.json"
        );
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(output.toFile(), snapshot);

        assertThat(snapshot.releaseCorpusQualified()).isTrue();
        assertThat(snapshot.caseCount()).isGreaterThanOrEqualTo(300);
    }

    private CaseResult evaluate(
            RagBenchmarkDataset.Query query,
            List<RetrievalHit> ranked,
            RagResponse response,
            RagExecutionObservationStore.Observation observation
    ) {
        Set<String> relevant = Set.copyOf(query.relevantChunkIds());
        List<String> rankedChunkIds = ranked.stream()
                .map(RetrievalHit::chunkId)
                .toList();

        int firstRelevantRank = query.answerable()
                ? firstRelevantRank(rankedChunkIds, relevant)
                : 0;
        double recall1 = query.answerable()
                ? recallAt(rankedChunkIds, relevant, 1)
                : 0.0;
        double recall5 = query.answerable()
                ? recallAt(rankedChunkIds, relevant, 5)
                : 0.0;
        double recall10 = query.answerable()
                ? recallAt(rankedChunkIds, relevant, 10)
                : 0.0;
        double mrr = firstRelevantRank > 0 ? 1.0 / firstRelevantRank : 0.0;
        double ndcg10 = query.answerable()
                ? ndcgAt10(rankedChunkIds, relevant)
                : 0.0;

        List<String> selectedChunks = observation == null
                ? List.of()
                : observation.selectedChunkIds();
        long relevantSelected = selectedChunks.stream()
                .filter(relevant::contains)
                .distinct()
                .count();
        double contextRecall = query.answerable() && !relevant.isEmpty()
                ? (double) relevantSelected / relevant.size()
                : 0.0;
        double contextPrecision = query.answerable() && !selectedChunks.isEmpty()
                ? (double) relevantSelected / selectedChunks.size()
                : 0.0;
        double evidenceDensity = query.answerable()
                ? evidenceDensity(relevant, observation)
                : 0.0;

        boolean abstained = response.sources().isEmpty();
        boolean falseAnswer = !query.answerable() && !abstained;
        boolean falseAbstention = query.answerable() && abstained;

        return new CaseResult(
                query.id(),
                query.language(),
                query.domain().name(),
                query.queryClass().name(),
                query.answerable(),
                abstained,
                falseAnswer,
                falseAbstention,
                recall1,
                recall5,
                recall10,
                mrr,
                ndcg10,
                contextRecall,
                contextPrecision,
                evidenceDensity
        );
    }

    private double evidenceDensity(
            Set<String> relevant,
            RagExecutionObservationStore.Observation observation
    ) {
        if (observation == null || observation.selectedTokenEstimates().isEmpty()) {
            return 0.0;
        }
        long totalTokens = observation.selectedTokenEstimates().values().stream()
                .mapToLong(Integer::longValue)
                .sum();
        long relevantTokens = observation.selectedTokenEstimates().entrySet().stream()
                .filter(entry -> relevant.contains(entry.getKey()))
                .mapToLong(entry -> entry.getValue())
                .sum();
        return totalTokens <= 0 ? 0.0 : (double) relevantTokens / totalTokens;
    }

    private RagQualitySnapshot snapshot(List<CaseResult> results) {
        RagQualityMetrics overall = aggregate(results);
        RagRuntimeAttribution.Snapshot attribution = runtimeAttribution.snapshot();
        return new RagQualitySnapshot(
                dataset.benchmarkVersion(),
                dataset.corpusVersion(),
                attribution.gitSha(),
                attribution.embeddingProfileId(),
                attribution.retrievalPolicyVersion(),
                attribution.learningPolicyVersion(),
                attribution.groundingPolicyVersion(),
                attribution.runtimeProfile(),
                results.size(),
                true,
                overall,
                slices(results, CaseResult::language),
                slices(results, CaseResult::domain),
                slices(results, CaseResult::queryClass),
                Instant.now()
        );
    }

    private Map<String, RagQualityMetrics> slices(
            List<CaseResult> results,
            Function<CaseResult, String> classifier
    ) {
        return results.stream()
                .collect(Collectors.groupingBy(classifier))
                .entrySet()
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> aggregate(entry.getValue())
                ));
    }

    private RagQualityMetrics aggregate(List<CaseResult> results) {
        List<CaseResult> answerable = results.stream()
                .filter(CaseResult::answerable)
                .toList();
        List<CaseResult> unanswerable = results.stream()
                .filter(value -> !value.answerable())
                .toList();
        long abstentions = results.stream().filter(CaseResult::abstained).count();
        long correctAbstentions = unanswerable.stream()
                .filter(CaseResult::abstained)
                .count();
        long falseAnswers = results.stream().filter(CaseResult::falseAnswer).count();
        long falseAbstentions = results.stream()
                .filter(CaseResult::falseAbstention)
                .count();

        return new RagQualityMetrics(
                average(answerable, CaseResult::recall1),
                average(answerable, CaseResult::recall5),
                average(answerable, CaseResult::recall10),
                average(answerable, CaseResult::mrr),
                average(answerable, CaseResult::ndcg10),
                average(answerable, CaseResult::contextRecall),
                average(answerable, CaseResult::contextPrecision),
                average(answerable, CaseResult::evidenceDensity),
                ratio(correctAbstentions, abstentions),
                ratio(correctAbstentions, unanswerable.size()),
                ratio(falseAnswers, unanswerable.size()),
                ratio(falseAbstentions, answerable.size())
        );
    }

    private double average(
            List<CaseResult> values,
            java.util.function.ToDoubleFunction<CaseResult> metric
    ) {
        if (values.isEmpty()) {
            return 0.0;
        }
        return values.stream().mapToDouble(metric).average().orElse(0.0);
    }

    private double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0.0 : (double) numerator / denominator;
    }

    private int firstRelevantRank(List<String> ranked, Set<String> relevant) {
        for (int index = 0; index < ranked.size(); index++) {
            if (relevant.contains(ranked.get(index))) {
                return index + 1;
            }
        }
        return 0;
    }

    private double recallAt(
            List<String> ranked,
            Set<String> relevant,
            int k
    ) {
        if (relevant.isEmpty()) {
            return 0.0;
        }
        long found = ranked.stream()
                .limit(k)
                .filter(relevant::contains)
                .distinct()
                .count();
        return (double) found / relevant.size();
    }

    private double ndcgAt10(List<String> ranked, Set<String> relevant) {
        if (relevant.isEmpty()) {
            return 0.0;
        }
        double dcg = 0.0;
        for (int index = 0; index < Math.min(10, ranked.size()); index++) {
            if (relevant.contains(ranked.get(index))) {
                dcg += 1.0 / log2(index + 2.0);
            }
        }
        double ideal = 0.0;
        for (int index = 0; index < Math.min(10, relevant.size()); index++) {
            ideal += 1.0 / log2(index + 2.0);
        }
        return ideal == 0.0 ? 0.0 : dcg / ideal;
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
            String id,
            String language,
            String domain,
            String queryClass,
            boolean answerable,
            boolean abstained,
            boolean falseAnswer,
            boolean falseAbstention,
            double recall1,
            double recall5,
            double recall10,
            double mrr,
            double ndcg10,
            double contextRecall,
            double contextPrecision,
            double evidenceDensity
    ) {
    }
}
