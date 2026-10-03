package kz.alimbetov.akmai.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@EnabledIfEnvironmentVariable(
        named = "AKMAI_RUN_RETRIEVAL_DECISION_MATRIX",
        matches = "(?i)true|1|yes"
)
class RetrievalStorageDecisionMatrixBenchmarkTest {

    private static final int TOP_K = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres")
            ).withDatabaseName("akmai_matrix")
                    .withUsername("akmai")
                    .withPassword("akmai");

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static Connection benchmarkConnection;
    private static MatrixConfig config;
    private static float[] queryVector;

    @BeforeAll
    static void setup() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        jdbc = new JdbcTemplate(dataSource);
        benchmarkConnection = dataSource.getConnection();
        config = MatrixConfig.fromEnvironment();
        queryVector = embedding(1L, config.dimensions());

        createSchema();
        seedCorpus();
        createIndexes();
        analyze();
    }

    @AfterAll
    static void closeBenchmarkConnection() throws Exception {
        if (benchmarkConnection != null) {
            benchmarkConnection.close();
        }
    }

    @Test
    void benchmarkAclScopeAndConcurrencyMatrix() throws Exception {
        List<ScopeResult> scopeResults = new ArrayList<>();

        for (int scopeSize : scopeSizes(config.accessLevels())) {
            PreparedQuery query = multiAclQuery(scopeSize, TOP_K);
            List<Long> exact = exactGroundTruth(scopeSize);

            for (int ignored = 0;
                    ignored < config.warmupIterations();
                    ignored++) {
                queryIds(benchmarkConnection, query);
            }

            long[] nanos = new long[config.measureIterations()];
            List<Long> last = List.of();
            for (int index = 0;
                    index < config.measureIterations();
                    index++) {
                long started = System.nanoTime();
                last = queryIds(benchmarkConnection, query);
                nanos[index] = System.nanoTime() - started;
            }

            PlanSummary plan = summarizePlan(
                    explain(benchmarkConnection, query)
            );
            assertAuthorizedPartitionsOnly(plan, scopeSize);

            scopeResults.add(new ScopeResult(
                    scopeSize,
                    recallAtK(last, exact),
                    percentileMillis(nanos, 0.50),
                    percentileMillis(nanos, 0.95),
                    percentileMillis(nanos, 0.99),
                    plan.planningTimeMs(),
                    plan.executionTimeMs(),
                    plan.sharedHitBlocks(),
                    plan.sharedReadBlocks(),
                    plan.executedRelations(),
                    plan.indexNames(),
                    plan.executedRelationRows()
            ));
        }

        int widestScope = config.accessLevels();
        List<Long> widestExact = exactGroundTruth(widestScope);

        List<QualityTuningResult> qualityTuningResults =
                tuneQuality(widestScope, widestExact);
        QualityTuningResult selectedTuning = qualityTuningResults.stream()
                .filter(result -> result.recallAtK() >= 0.999)
                .min(java.util.Comparator
                        .comparingDouble(QualityTuningResult::p95Ms)
                        .thenComparingInt(QualityTuningResult::candidateMultiplier)
                        .thenComparingInt(QualityTuningResult::efSearch))
                .orElseThrow(() -> new AssertionError(
                        "No ANN fan-out tuning reached Recall@10 = 1.0"
                ));

        List<ConcurrencyResult> concurrencyResults = new ArrayList<>();
        List<ConcurrencyResult> tunedConcurrencyResults = new ArrayList<>();

        for (int concurrency : config.concurrencyLevels()) {
            concurrencyResults.add(runConcurrent(
                    multiAclQuery(widestScope, TOP_K),
                    widestExact,
                    concurrency,
                    config.defaultEfSearch()
            ));
            tunedConcurrencyResults.add(runConcurrent(
                    multiAclQuery(
                            widestScope,
                            TOP_K * selectedTuning.candidateMultiplier()
                    ),
                    widestExact,
                    concurrency,
                    selectedTuning.efSearch()
            ));
        }

        Path output = Path.of(
                "target",
                "retrieval-benchmark",
                "decision-matrix-acl-" + config.accessLevels() + ".json"
        );
        Files.createDirectories(output.getParent());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("postgresImage", "pgvector/pgvector:pg17");
        report.put("dimensions", config.dimensions());
        report.put("accessLevels", config.accessLevels());
        report.put("rowsPerAccessLevel", config.rowsPerAccessLevel());
        report.put("totalRows", config.totalRows());
        report.put("chunksPerDocument", config.chunksPerDocument());
        report.put("topK", TOP_K);
        report.put("scopeResults", scopeResults);
        report.put("qualityTuningResults", qualityTuningResults);
        report.put("selectedTuning", selectedTuning);
        report.put("concurrencyResults", concurrencyResults);
        report.put("tunedConcurrencyResults", tunedConcurrencyResults);

        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(output.toFile(), report);

        assertThat(scopeResults)
                .extracting(ScopeResult::recallAtK)
                .allSatisfy(recall ->
                        assertThat(recall).isBetween(0.0, 1.0)
                );
        assertThat(concurrencyResults)
                .extracting(ConcurrencyResult::recallAtK)
                .allSatisfy(recall ->
                        assertThat(recall).isBetween(0.0, 1.0)
                );
        assertThat(tunedConcurrencyResults)
                .extracting(ConcurrencyResult::recallAtK)
                .allSatisfy(recall ->
                        assertThat(recall).isEqualTo(1.0)
                );
    }

    private static void createSchema() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");

        jdbc.execute("""
                CREATE TABLE matrix_lifecycle (
                    document_id VARCHAR(100) PRIMARY KEY,
                    published_generation BIGINT NOT NULL,
                    access_level BIGINT NOT NULL,
                    retention_status VARCHAR(32) NOT NULL
                )
                """);

        jdbc.execute("""
                CREATE TABLE matrix_list (
                    access_level BIGINT NOT NULL,
                    id BIGINT NOT NULL,
                    document_id VARCHAR(100) NOT NULL,
                    generation BIGINT NOT NULL,
                    embedding VECTOR(%d) NOT NULL,
                    PRIMARY KEY (access_level, id)
                )
                PARTITION BY LIST (access_level)
                """.formatted(config.dimensions()));

        for (int accessLevel = 1;
                accessLevel <= config.accessLevels();
                accessLevel++) {
            jdbc.execute("""
                    CREATE TABLE matrix_list_al_%d
                    PARTITION OF matrix_list
                    FOR VALUES IN (%d)
                    """.formatted(accessLevel, accessLevel));
        }
    }

    private static void seedCorpus() {
        long id = 1L;

        for (int accessLevel = 1;
                accessLevel <= config.accessLevels();
                accessLevel++) {
            int rowsRemaining = config.rowsPerAccessLevel();
            int documentIndex = 0;
            List<MatrixRow> batch = new ArrayList<>(500);

            while (rowsRemaining > 0) {
                String documentId = documentId(
                        accessLevel,
                        documentIndex
                );
                int documentRows = Math.min(
                        config.chunksPerDocument(),
                        rowsRemaining
                );

                jdbc.update(
                        """
                        INSERT INTO matrix_lifecycle (
                            document_id,
                            published_generation,
                            access_level,
                            retention_status
                        ) VALUES (?, 1, ?, 'ACTIVE')
                        """,
                        documentId,
                        accessLevel
                );

                for (int chunk = 0;
                        chunk < documentRows;
                        chunk++) {
                    batch.add(new MatrixRow(
                            id,
                            accessLevel,
                            documentId,
                            embedding(id, config.dimensions())
                    ));
                    id++;

                    if (batch.size() == 500) {
                        insertBatch(batch);
                        batch.clear();
                    }
                }

                rowsRemaining -= documentRows;
                documentIndex++;
            }

            if (!batch.isEmpty()) {
                insertBatch(batch);
            }
        }
    }

    private static void insertBatch(List<MatrixRow> rows) {
        jdbc.batchUpdate(
                """
                INSERT INTO matrix_list (
                    access_level,
                    id,
                    document_id,
                    generation,
                    embedding
                ) VALUES (?, ?, ?, 1, ?)
                """,
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setLong(1, row.accessLevel());
                    ps.setLong(2, row.id());
                    ps.setString(3, row.documentId());
                    ps.setObject(4, new PGvector(row.embedding()));
                }
        );
    }

    private static void createIndexes() {
        jdbc.execute("""
                CREATE INDEX matrix_list_embedding_hnsw
                ON matrix_list
                USING HNSW (embedding vector_cosine_ops)
                """);

        jdbc.execute("""
                CREATE INDEX matrix_list_document_generation
                ON matrix_list (
                    document_id,
                    generation
                )
                """);
    }

    private static void analyze() {
        jdbc.execute("ANALYZE matrix_lifecycle");
        jdbc.execute("ANALYZE matrix_list");
    }

    private static PreparedQuery multiAclQuery(
            int scopeSize,
            int branchCandidateLimit
    ) {
        if (scopeSize <= 0 || scopeSize > config.accessLevels()) {
            throw new IllegalArgumentException(
                    "Invalid ACL scope size: " + scopeSize
            );
        }
        if (branchCandidateLimit < TOP_K) {
            throw new IllegalArgumentException(
                    "branchCandidateLimit must be >= TOP_K"
            );
        }

        StringBuilder sql = new StringBuilder("""
                SELECT candidate.id
                FROM (
                """);
        List<Object> parameters = new ArrayList<>();

        for (int accessLevel = 1;
                accessLevel <= scopeSize;
                accessLevel++) {
            if (accessLevel > 1) {
                sql.append("\nUNION ALL\n");
            }

            sql.append("""
                    (
                        SELECT
                            v.id,
                            v.document_id,
                            v.generation,
                            v.access_level,
                            v.embedding <=> ? AS distance
                        FROM matrix_list v
                        WHERE v.access_level = ?
                        ORDER BY v.embedding <=> ?
                        LIMIT ?
                    )
                    """);

            parameters.add(new PGvector(queryVector));
            parameters.add((long) accessLevel);
            parameters.add(new PGvector(queryVector));
            parameters.add(branchCandidateLimit);
        }

        sql.append("""
                ) candidate
                JOIN matrix_lifecycle l
                  ON l.document_id = candidate.document_id
                 AND l.published_generation = candidate.generation
                 AND l.access_level = candidate.access_level
                WHERE l.retention_status = 'ACTIVE'
                ORDER BY candidate.distance
                LIMIT ?
                """);
        parameters.add(TOP_K);

        return new PreparedQuery(
                sql.toString(),
                List.copyOf(parameters)
        );
    }

    private static List<Long> exactGroundTruth(int scopeSize) {
        String placeholders = String.join(
                ",",
                java.util.Collections.nCopies(scopeSize, "?")
        );
        String sql = """
                WITH candidates AS MATERIALIZED (
                    SELECT v.id, v.embedding
                    FROM matrix_list v
                    JOIN matrix_lifecycle l
                      ON l.document_id = v.document_id
                     AND l.published_generation = v.generation
                     AND l.access_level = v.access_level
                    WHERE v.access_level IN (%s)
                      AND l.retention_status = 'ACTIVE'
                )
                SELECT id
                FROM candidates
                ORDER BY embedding <=> ?
                LIMIT ?
                """.formatted(placeholders);

        List<Object> parameters = new ArrayList<>();
        for (int accessLevel = 1;
                accessLevel <= scopeSize;
                accessLevel++) {
            parameters.add((long) accessLevel);
        }
        parameters.add(new PGvector(queryVector));
        parameters.add(TOP_K);

        return queryIds(
                benchmarkConnection,
                new PreparedQuery(sql, List.copyOf(parameters))
        );
    }

    private static List<QualityTuningResult> tuneQuality(
            int scopeSize,
            List<Long> exact
    ) throws Exception {
        List<QualityTuningResult> results = new ArrayList<>();

        for (int candidateMultiplier : config.candidateMultipliers()) {
            for (int efSearch : config.efSearchValues()) {
                PreparedQuery query = multiAclQuery(
                        scopeSize,
                        TOP_K * candidateMultiplier
                );
                results.add(measureQualityTuning(
                        query,
                        exact,
                        candidateMultiplier,
                        efSearch
                ));
            }
        }

        return List.copyOf(results);
    }

    private static QualityTuningResult measureQualityTuning(
            PreparedQuery query,
            List<Long> exact,
            int candidateMultiplier,
            int efSearch
    ) throws Exception {
        setRetrievalSession(benchmarkConnection, efSearch);

        try (PreparedStatement ps =
                benchmarkConnection.prepareStatement(query.sql())) {
            for (int ignored = 0;
                    ignored < config.warmupIterations();
                    ignored++) {
                executeIds(ps, query.parameters());
            }

            long[] nanos = new long[config.measureIterations()];
            List<Long> last = List.of();

            for (int index = 0;
                    index < config.measureIterations();
                    index++) {
                long started = System.nanoTime();
                last = executeIds(ps, query.parameters());
                nanos[index] = System.nanoTime() - started;
            }

            return new QualityTuningResult(
                    candidateMultiplier,
                    TOP_K * candidateMultiplier,
                    efSearch,
                    recallAtK(last, exact),
                    percentileMillis(nanos, 0.50),
                    percentileMillis(nanos, 0.95),
                    percentileMillis(nanos, 0.99)
            );
        } finally {
            resetRetrievalSession(benchmarkConnection);
        }
    }

    private static ConcurrencyResult runConcurrent(
            PreparedQuery query,
            List<Long> exact,
            int concurrency,
            int efSearch
    ) throws Exception {
        ExecutorService executor =
                Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WorkerResult>> futures = new ArrayList<>();

        try {
            for (int worker = 0; worker < concurrency; worker++) {
                futures.add(executor.submit(() -> runWorker(
                        query,
                        ready,
                        start,
                        efSearch
                )));
            }

            assertThat(ready.await(2, TimeUnit.MINUTES)).isTrue();

            long wallStarted = System.nanoTime();
            start.countDown();

            List<Long> allLatencies = new ArrayList<>();
            double minimumRecall = 1.0;
            for (Future<WorkerResult> future : futures) {
                WorkerResult result = future.get(
                        10,
                        TimeUnit.MINUTES
                );
                for (long latency : result.latenciesNanos()) {
                    allLatencies.add(latency);
                }
                minimumRecall = Math.min(
                        minimumRecall,
                        recallAtK(result.lastIds(), exact)
                );
            }
            long wallNanos = System.nanoTime() - wallStarted;

            long[] latencies = allLatencies.stream()
                    .mapToLong(Long::longValue)
                    .toArray();
            double throughput = allLatencies.size()
                    / (wallNanos / 1_000_000_000.0);

            return new ConcurrencyResult(
                    concurrency,
                    config.accessLevels(),
                    minimumRecall,
                    percentileMillis(latencies, 0.50),
                    percentileMillis(latencies, 0.95),
                    percentileMillis(latencies, 0.99),
                    throughput,
                    allLatencies.size()
            );
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private static WorkerResult runWorker(
            PreparedQuery query,
            CountDownLatch ready,
            CountDownLatch start,
            int efSearch
    ) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            setRetrievalSession(connection, efSearch);

            try (PreparedStatement ps =
                    connection.prepareStatement(query.sql())) {
                for (int ignored = 0;
                        ignored < config.warmupIterations();
                        ignored++) {
                    executeIds(ps, query.parameters());
                }

                ready.countDown();
                start.await();

                long[] latencies =
                        new long[config.concurrentIterations()];
                List<Long> last = List.of();

                for (int index = 0;
                        index < config.concurrentIterations();
                        index++) {
                    long started = System.nanoTime();
                    last = executeIds(ps, query.parameters());
                    latencies[index] = System.nanoTime() - started;
                }

                return new WorkerResult(latencies, last);
            } finally {
                resetRetrievalSession(connection);
            }
        }
    }

    private static List<Long> queryIds(
            Connection connection,
            PreparedQuery query
    ) {
        try {
            setRetrievalSession(connection);
            try (PreparedStatement ps =
                    connection.prepareStatement(query.sql())) {
                return executeIds(ps, query.parameters());
            } finally {
                resetRetrievalSession(connection);
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(
                    "Matrix benchmark query failed",
                    exception
            );
        }
    }

    private static List<Long> executeIds(
            PreparedStatement ps,
            List<Object> parameters
    ) throws java.sql.SQLException {
        bind(ps, parameters);

        try (ResultSet rs = ps.executeQuery()) {
            List<Long> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
            return List.copyOf(ids);
        }
    }

    private static String explain(
            Connection connection,
            PreparedQuery query
    ) {
        try {
            setRetrievalSession(connection);

            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    EXPLAIN (
                        ANALYZE,
                        BUFFERS,
                        SETTINGS,
                        SUMMARY,
                        FORMAT JSON
                    )
                    """ + query.sql()
            )) {
                bind(ps, query.parameters());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            } finally {
                resetRetrievalSession(connection);
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(
                    "Matrix benchmark EXPLAIN failed",
                    exception
            );
        }
    }

    private static void bind(
            PreparedStatement ps,
            List<Object> parameters
    ) throws java.sql.SQLException {
        for (int index = 0;
                index < parameters.size();
                index++) {
            ps.setObject(index + 1, parameters.get(index));
        }
    }

    private static void setRetrievalSession(
            Connection connection
    ) throws java.sql.SQLException {
        setRetrievalSession(connection, config.defaultEfSearch());
    }

    private static void setRetrievalSession(
            Connection connection,
            int efSearch
    ) throws java.sql.SQLException {
        if (efSearch <= 0) {
            throw new IllegalArgumentException(
                    "efSearch must be positive"
            );
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("SET hnsw.iterative_scan = strict_order");
            statement.execute("SET hnsw.ef_search = " + efSearch);
        }
    }

    private static void resetRetrievalSession(
            Connection connection
    ) throws java.sql.SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET hnsw.ef_search");
            statement.execute("RESET hnsw.iterative_scan");
        }
    }

    private static void assertAuthorizedPartitionsOnly(
            PlanSummary plan,
            int scopeSize
    ) {
        Set<String> allowed = new LinkedHashSet<>();
        for (int accessLevel = 1;
                accessLevel <= scopeSize;
                accessLevel++) {
            allowed.add("matrix_list_al_" + accessLevel);
        }

        Set<String> touchedPartitions = new LinkedHashSet<>();
        for (String relation : plan.executedRelations()) {
            if (relation.startsWith("matrix_list_al_")) {
                touchedPartitions.add(relation);
            }
        }

        assertThat(touchedPartitions)
                .isNotEmpty()
                .isSubsetOf(allowed);
    }

    private static PlanSummary summarizePlan(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode top = root.get(0);

            Set<String> relations = new LinkedHashSet<>();
            Set<String> indexes = new LinkedHashSet<>();
            Map<String, Long> relationRows = new LinkedHashMap<>();

            walkPlan(
                    top.path("Plan"),
                    relations,
                    indexes,
                    relationRows
            );

            JsonNode rootPlan = top.path("Plan");
            return new PlanSummary(
                    top.path("Planning Time").asDouble(),
                    top.path("Execution Time").asDouble(),
                    rootPlan.path("Shared Hit Blocks").asLong(),
                    rootPlan.path("Shared Read Blocks").asLong(),
                    Set.copyOf(relations),
                    Set.copyOf(indexes),
                    Map.copyOf(relationRows)
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot parse matrix EXPLAIN JSON",
                    exception
            );
        }
    }

    private static void walkPlan(
            JsonNode node,
            Set<String> relations,
            Set<String> indexes,
            Map<String, Long> relationRows
    ) {
        if (node == null || node.isMissingNode()) {
            return;
        }

        long loops = node.path("Actual Loops").asLong(1L);
        if (loops > 0) {
            String relation = node.path("Relation Name").asText("");
            if (!relation.isBlank()) {
                relations.add(relation);
                relationRows.merge(
                        relation,
                        node.path("Actual Rows").asLong() * loops,
                        Math::max
                );
            }

            String index = node.path("Index Name").asText("");
            if (!index.isBlank()) {
                indexes.add(index);
            }
        }

        JsonNode plans = node.path("Plans");
        if (plans.isArray()) {
            for (JsonNode child : plans) {
                walkPlan(
                        child,
                        relations,
                        indexes,
                        relationRows
                );
            }
        }
    }

    private static List<Integer> scopeSizes(int accessLevels) {
        List<Integer> result = new ArrayList<>();
        int value = 1;

        while (value < accessLevels) {
            result.add(value);
            value *= 2;
        }
        result.add(accessLevels);

        return result.stream().distinct().toList();
    }

    private static double recallAtK(
            List<Long> actual,
            List<Long> expected
    ) {
        if (expected.isEmpty()) {
            return 1.0;
        }

        Set<Long> expectedSet = new LinkedHashSet<>(expected);
        long matches = actual.stream()
                .distinct()
                .filter(expectedSet::contains)
                .count();

        return (double) matches / expectedSet.size();
    }

    private static double percentileMillis(
            long[] nanos,
            double percentile
    ) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        int index = Math.min(
                sorted.length - 1,
                Math.max(
                        0,
                        (int) Math.ceil(percentile * sorted.length) - 1
                )
        );
        return sorted[index]
                / (double) TimeUnit.MILLISECONDS.toNanos(1);
    }

    private static float[] embedding(
            long seed,
            int dimensions
    ) {
        SplittableRandom random = new SplittableRandom(
                0x9E3779B97F4A7C15L ^ seed
        );
        float[] values = new float[dimensions];
        double norm = 0.0;

        for (int index = 0; index < dimensions; index++) {
            double value = random.nextDouble(-1.0, 1.0);
            values[index] = (float) value;
            norm += value * value;
        }

        double scale = Math.sqrt(norm);
        for (int index = 0; index < dimensions; index++) {
            values[index] = (float) (values[index] / scale);
        }

        return values;
    }

    private static String documentId(
            int accessLevel,
            int index
    ) {
        return "acl-" + accessLevel
                + "-doc-"
                + String.format(Locale.ROOT, "%05d", index);
    }

    private record MatrixRow(
            long id,
            long accessLevel,
            String documentId,
            float[] embedding
    ) {
        private MatrixRow {
            embedding = embedding.clone();
        }

        @Override
        public float[] embedding() {
            return embedding.clone();
        }
    }

    private record PreparedQuery(
            String sql,
            List<Object> parameters
    ) {
    }

    private record WorkerResult(
            long[] latenciesNanos,
            List<Long> lastIds
    ) {
        private WorkerResult {
            latenciesNanos = latenciesNanos.clone();
            lastIds = List.copyOf(lastIds);
        }

        @Override
        public long[] latenciesNanos() {
            return latenciesNanos.clone();
        }
    }

    private record PlanSummary(
            double planningTimeMs,
            double executionTimeMs,
            long sharedHitBlocks,
            long sharedReadBlocks,
            Set<String> executedRelations,
            Set<String> indexNames,
            Map<String, Long> executedRelationRows
    ) {
    }

    private record ScopeResult(
            int scopeSize,
            double recallAtK,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double explainPlanningMs,
            double explainExecutionMs,
            long sharedHitBlocks,
            long sharedReadBlocks,
            Set<String> executedRelations,
            Set<String> indexNames,
            Map<String, Long> executedRelationRows
    ) {
    }

    private record QualityTuningResult(
            int candidateMultiplier,
            int branchCandidateLimit,
            int efSearch,
            double recallAtK,
            double p50Ms,
            double p95Ms,
            double p99Ms
    ) {
    }

    private record ConcurrencyResult(
            int concurrency,
            int scopeSize,
            double recallAtK,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double throughputQueriesPerSecond,
            int measuredQueries
    ) {
    }

    private record MatrixConfig(
            int dimensions,
            int accessLevels,
            int rowsPerAccessLevel,
            int chunksPerDocument,
            int warmupIterations,
            int measureIterations,
            int concurrentIterations,
            List<Integer> concurrencyLevels,
            int defaultEfSearch,
            List<Integer> candidateMultipliers,
            List<Integer> efSearchValues
    ) {
        private static MatrixConfig fromEnvironment() {
            int accessLevels = intValue(
                    "AKMAI_MATRIX_ACCESS_LEVELS",
                    16
            );
            List<Integer> concurrency = intList(
                    "AKMAI_MATRIX_CONCURRENCY",
                    "1,4,16"
            );

            return new MatrixConfig(
                    intValue(
                            "AKMAI_MATRIX_DIMENSIONS",
                            1024
                    ),
                    accessLevels,
                    intValue(
                            "AKMAI_MATRIX_ROWS_PER_ACL",
                            2000
                    ),
                    intValue(
                            "AKMAI_MATRIX_CHUNKS_PER_DOCUMENT",
                            300
                    ),
                    intValue(
                            "AKMAI_MATRIX_WARMUPS",
                            3
                    ),
                    intValue(
                            "AKMAI_MATRIX_ITERATIONS",
                            10
                    ),
                    intValue(
                            "AKMAI_MATRIX_CONCURRENT_ITERATIONS",
                            10
                    ),
                    concurrency,
                    intValue(
                            "AKMAI_MATRIX_DEFAULT_EF_SEARCH",
                            40
                    ),
                    intList(
                            "AKMAI_MATRIX_CANDIDATE_MULTIPLIERS",
                            "1,2,4"
                    ),
                    intList(
                            "AKMAI_MATRIX_EF_SEARCH_VALUES",
                            "40,80,120"
                    )
            );
        }

        private int totalRows() {
            return Math.multiplyExact(
                    accessLevels,
                    rowsPerAccessLevel
            );
        }

        private static int intValue(
                String name,
                int defaultValue
        ) {
            String raw = System.getenv(name);
            if (raw == null || raw.isBlank()) {
                return defaultValue;
            }

            int value = Integer.parseInt(raw);
            if (value <= 0) {
                throw new IllegalArgumentException(
                        name + " must be positive"
                );
            }
            return value;
        }

        private static List<Integer> intList(
                String name,
                String defaultValue
        ) {
            String raw = System.getenv(name);
            if (raw == null || raw.isBlank()) {
                raw = defaultValue;
            }

            List<Integer> result = Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(Integer::parseInt)
                    .peek(value -> {
                        if (value <= 0) {
                            throw new IllegalArgumentException(
                                    name + " values must be positive"
                            );
                        }
                    })
                    .distinct()
                    .sorted()
                    .toList();

            if (result.isEmpty()) {
                throw new IllegalArgumentException(
                        name + " must not be empty"
                );
            }
            return result;
        }
    }
}
