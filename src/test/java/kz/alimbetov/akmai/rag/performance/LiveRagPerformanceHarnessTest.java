package kz.alimbetov.akmai.rag.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.service.KnowledgeIngestionPort;
import kz.alimbetov.akmai.rag.api.RagResponse;
import kz.alimbetov.akmai.rag.quality.RagBenchmarkV1Corpus;
import kz.alimbetov.akmai.rag.service.RagQuestionService;
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
@EnabledIfEnvironmentVariable(named = "AKMAI_LIVE_PERFORMANCE", matches = "true")
class LiveRagPerformanceHarnessTest {

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
    RagQuestionService questionService;
    @Autowired
    MeterRegistry meterRegistry;
    @Autowired
    ObjectMapper objectMapper;

    @BeforeAll
    void ingestCorpusAndWarmUp() {
        int index = 0;
        for (RagBenchmarkV1Corpus.Case testCase : RagBenchmarkV1Corpus.smokeCases()) {
            ingestion.addText(
                    new AddKnowledgeRequest(
                            "perf-" + testCase.id(),
                            testCase.title(),
                            testCase.text(),
                            "benchmark://performance/" + testCase.id(),
                            testCase.language(),
                            testCase.domain(),
                            1L,
                            Map.of("performanceCase", testCase.id())
                    ),
                    "perf-ingest-" + testCase.id()
            );
            if (index++ < 3) {
                questionService.ask(testCase.question(), Set.of(1L));
            }
        }
    }

    @Test
    void producesConcurrentReadAndMixedLoadPerformanceBaseline() throws Exception {
        ScenarioResult read = runReadScenario(
                intEnv("AKMAI_LIVE_PERFORMANCE_CONCURRENCY", 4),
                intEnv("AKMAI_LIVE_PERFORMANCE_REQUESTS", 22)
        );
        ScenarioResult mixed = runMixedScenario(
                Math.max(2, intEnv("AKMAI_LIVE_PERFORMANCE_CONCURRENCY", 4)),
                Math.max(10, intEnv("AKMAI_LIVE_PERFORMANCE_MIXED_REQUESTS", 20))
        );

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("benchmarkVersion", "rag-performance-v1");
        report.put("generatedAt", Instant.now().toString());
        report.put("hardware", hardwareProfile());
        report.put("scenarios", List.of(read, mixed));
        report.put("stages", stageSnapshot());
        report.put("saturation", akmaiMeterSnapshot());

        Path output = Path.of("target", "performance", "performance-baseline.json");
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);

        double p95Limit = doubleEnv("AKMAI_LIVE_PERFORMANCE_P95_MS", 15_000.0);
        assertThat(read.failed()).isZero();
        assertThat(mixed.failed()).isZero();
        assertThat(read.p95Millis()).isLessThanOrEqualTo(p95Limit);
    }

    private ScenarioResult runReadScenario(int concurrency, int requestCount)
            throws Exception {
        List<RagBenchmarkV1Corpus.Case> corpus = RagBenchmarkV1Corpus.smokeCases();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int index = 0; index < requestCount; index++) {
            var testCase = corpus.get(index % corpus.size());
            tasks.add(() -> {
                RagResponse response = questionService.ask(testCase.question(), Set.of(1L));
                return !response.sources().isEmpty();
            });
        }
        return execute("read-concurrent", concurrency, tasks);
    }

    private ScenarioResult runMixedScenario(int concurrency, int operationCount)
            throws Exception {
        List<RagBenchmarkV1Corpus.Case> corpus = RagBenchmarkV1Corpus.smokeCases();
        AtomicInteger writeSequence = new AtomicInteger();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int index = 0; index < operationCount; index++) {
            if (index % 10 == 0) {
                tasks.add(() -> {
                    int sequence = writeSequence.incrementAndGet();
                    ingestion.addText(
                            new AddKnowledgeRequest(
                                    "perf-mixed-write-" + sequence,
                                    "Performance write " + sequence,
                                    "Technical runbook entry " + sequence
                                            + ": bounded ingestion must preserve publication isolation and idempotency.",
                                    "benchmark://performance/mixed/" + sequence,
                                    "en",
                                    KnowledgeDomain.TECHNICAL,
                                    1L,
                                    Map.of("scenario", "mixed-light")
                            ),
                            "perf-mixed-idempotency-" + sequence
                    );
                    return true;
                });
            } else {
                var testCase = corpus.get(index % corpus.size());
                tasks.add(() -> {
                    RagResponse response = questionService.ask(
                            testCase.question(),
                            Set.of(1L)
                    );
                    return !response.sources().isEmpty();
                });
            }
        }
        return execute("mixed-light", concurrency, tasks);
    }

    private ScenarioResult execute(
            String name,
            int concurrency,
            List<Callable<Boolean>> tasks
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Double> latenciesMillis = new ArrayList<>();
        int successful = 0;
        int abstained = 0;
        int failed = 0;
        long started = System.nanoTime();
        try {
            List<Future<TimedResult>> futures = new ArrayList<>();
            for (Callable<Boolean> task : tasks) {
                futures.add(executor.submit(() -> {
                    long itemStarted = System.nanoTime();
                    try {
                        boolean useful = task.call();
                        return new TimedResult(
                                useful,
                                false,
                                nanosToMillis(System.nanoTime() - itemStarted)
                        );
                    } catch (Exception exception) {
                        return new TimedResult(
                                false,
                                true,
                                nanosToMillis(System.nanoTime() - itemStarted)
                        );
                    }
                }));
            }
            for (Future<TimedResult> future : futures) {
                TimedResult result = future.get(60, TimeUnit.SECONDS);
                latenciesMillis.add(result.latencyMillis());
                if (result.failed()) {
                    failed++;
                } else if (result.useful()) {
                    successful++;
                } else {
                    abstained++;
                }
            }
        } finally {
            executor.shutdownNow();
        }
        double seconds = Math.max(
                0.001,
                (System.nanoTime() - started) / 1_000_000_000.0
        );
        return new ScenarioResult(
                name,
                concurrency,
                tasks.size(),
                successful,
                abstained,
                failed,
                tasks.size() / seconds,
                percentile(latenciesMillis, 0.50),
                percentile(latenciesMillis, 0.95),
                percentile(latenciesMillis, 0.99)
        );
    }

    private Map<String, Object> stageSnapshot() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (RagPipelineStage stage : RagPipelineStage.values()) {
            Timer timer = meterRegistry.find("akmai.rag.stage")
                    .tag("stage", stage.name().toLowerCase(java.util.Locale.ROOT))
                    .timer();
            if (timer == null || timer.count() == 0) {
                continue;
            }
            var snapshot = timer.takeSnapshot();
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("count", timer.count());
            values.put("meanMs", timer.mean(TimeUnit.MILLISECONDS));
            for (var percentile : snapshot.percentileValues()) {
                int rounded = (int) Math.round(percentile.percentile() * 100);
                values.put(
                        "p" + rounded + "Ms",
                        percentile.value(TimeUnit.MILLISECONDS)
                );
            }
            result.put(stage.name(), Map.copyOf(values));
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> akmaiMeterSnapshot() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        int count = 0;
        for (Meter meter : meterRegistry.getMeters()) {
            if (!meter.getId().getName().startsWith("akmai.")) {
                continue;
            }
            if (count++ >= 256) {
                break;
            }
            List<Double> values = new ArrayList<>();
            for (Measurement measurement : meter.measure()) {
                if (Double.isFinite(measurement.getValue())) {
                    values.add(measurement.getValue());
                }
            }
            result.put(
                    meter.getId().getName() + meter.getId().getTags(),
                    List.copyOf(values)
            );
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> hardwareProfile() {
        Runtime runtime = Runtime.getRuntime();
        return Map.of(
                "processors", runtime.availableProcessors(),
                "maxHeapBytes", runtime.maxMemory(),
                "javaVersion", System.getProperty("java.version", "unknown"),
                "os", System.getProperty("os.name", "unknown"),
                "arch", System.getProperty("os.arch", "unknown")
        );
    }

    private double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private double nanosToMillis(long nanos) {
        return Duration.ofNanos(Math.max(0, nanos)).toNanos() / 1_000_000.0;
    }

    private int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value.trim());
    }

    private double doubleEnv(String name, double fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Double.parseDouble(value.trim());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable " + name);
        }
        return value;
    }

    private record TimedResult(
            boolean useful,
            boolean failed,
            double latencyMillis
    ) {
    }

    private record ScenarioResult(
            String scenario,
            int concurrency,
            int operations,
            int successful,
            int abstained,
            int failed,
            double throughputPerSecond,
            double p50Millis,
            double p95Millis,
            double p99Millis
    ) {
    }
}
