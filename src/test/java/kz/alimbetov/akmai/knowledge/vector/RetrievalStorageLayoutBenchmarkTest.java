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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RetrievalStorageLayoutBenchmarkTest {

    private static final int CI_ROWS = 12_000;
    private static final int CI_DIMENSIONS = 8;
    private static final int CI_ACCESS_LEVELS = 4;
    private static final int CI_CHUNKS_PER_DOCUMENT = 100;
    private static final int TOP_K = 10;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres")
            ).withDatabaseName("akmai_benchmark")
                    .withUsername("akmai")
                    .withPassword("akmai");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JdbcTemplate jdbc;
    private static Connection benchmarkConnection;
    private static BenchmarkConfig config;
    private static float[] queryVector;

    @BeforeAll
    static void setup() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        jdbc = new JdbcTemplate(dataSource);
        benchmarkConnection = dataSource.getConnection();
        config = BenchmarkConfig.fromEnvironment();
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
    void listLayoutGenericPreparedPlanExecutesOnlyRequestedAclPartition() {
        String planJson = genericListPlan(1L, queryVector, TOP_K);
        PlanSummary plan = summarizePlan(planJson);

        Set<String> unrelatedPartitions = new LinkedHashSet<>();
        for (int accessLevel = 2;
                accessLevel <= config.accessLevels();
                accessLevel++) {
            unrelatedPartitions.add("bench_list_al_" + accessLevel);
        }

        assertThat(plan.executedRelations())
                .contains("bench_list_al_1")
                .doesNotContainAnyElementsOf(unrelatedPartitions);
        assertThat(plan.executedIndexRelations()).contains("bench_list_al_1");
    }

    @Test
    void listLayoutDocumentExactPathUsesBoundedCandidateSet() {
        String documentId = documentId(0);
        String sql = """
                WITH candidates AS MATERIALIZED (
                    SELECT v.id, v.embedding
                    FROM bench_list v
                    WHERE v.access_level = ?
                      AND v.document_id = ?
                      AND v.generation = 1
                )
                SELECT id, embedding <=> ? AS distance
                FROM candidates
                ORDER BY distance
                LIMIT ?
                """;

        String planJson = explain(
                sql,
                1L,
                documentId,
                new PGvector(queryVector),
                TOP_K
        );
        PlanSummary plan = summarizePlan(planJson);

        assertThat(plan.executedRelations()).contains("bench_list_al_1");
        assertThat(
                plan.executedRelationRows()
                        .getOrDefault("bench_list_al_1", Long.MAX_VALUE)
        ).isLessThanOrEqualTo(config.chunksPerDocument());
    }

    @Test
    void listLayoutReturnsOnlyRequestedAcl() {
        List<Long> ids = queryIds(
                """
                SELECT v.id
                FROM bench_list v
                WHERE v.access_level = ?
                ORDER BY v.embedding <=> ?
                LIMIT ?
                """,
                1L,
                new PGvector(queryVector),
                TOP_K
        );

        assertThat(ids).hasSize(TOP_K);
        assertThat(ids).allSatisfy(id ->
                assertThat(accessLevelForRow(id - 1))
                        .isEqualTo(1L)
        );
    }

    @Test
    @EnabledIfEnvironmentVariable(
            named = "AKMAI_RUN_RETRIEVAL_BENCHMARK",
            matches = "(?i)true|1|yes"
    )
    void benchmarkCandidateLayoutsAndWriteReport() throws IOException {
        List<Long> exact = exactGroundTruth(1L, queryVector, TOP_K);
        List<BenchmarkResult> results = new ArrayList<>();

        for (Layout layout : Layout.values()) {
            QuerySpec query = querySpec(layout);
            for (int ignored = 0; ignored < config.warmupIterations(); ignored++) {
                queryIds(
                        query.sql(),
                        1L,
                        new PGvector(queryVector),
                        TOP_K
                );
            }

            long[] nanos = new long[config.measureIterations()];
            List<Long> last = List.of();
            for (int i = 0; i < config.measureIterations(); i++) {
                long started = System.nanoTime();
                last = queryIds(
                        query.sql(),
                        1L,
                        new PGvector(queryVector),
                        TOP_K
                );
                nanos[i] = System.nanoTime() - started;
            }

            String planJson = explain(
                    query.sql(),
                    1L,
                    new PGvector(queryVector),
                    TOP_K
            );
            PlanSummary plan = summarizePlan(planJson);

            results.add(new BenchmarkResult(
                    layout.name(),
                    recallAtK(last, exact),
                    percentileMillis(nanos, 0.50),
                    percentileMillis(nanos, 0.95),
                    percentileMillis(nanos, 0.99),
                    plan.planningTimeMs(),
                    plan.executionTimeMs(),
                    plan.sharedHitBlocks(),
                    plan.sharedReadBlocks(),
                    plan.totalRowsVisited(),
                    plan.executedRelationRows(),
                    plan.executedRelations(),
                    plan.indexNames(),
                    planJson
            ));
        }

        List<Long> documentExact = documentExactGroundTruth(
                1L,
                documentId(0),
                queryVector,
                TOP_K
        );
        results.add(documentExactResult(documentExact));

        Path output = Path.of(
                "target",
                "retrieval-benchmark",
                "layout-report.json"
        );
        Files.createDirectories(output.getParent());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("postgresImage", "pgvector/pgvector:pg17");
        report.put("rows", config.rows());
        report.put("dimensions", config.dimensions());
        report.put("accessLevels", config.accessLevels());
        report.put("chunksPerDocument", config.chunksPerDocument());
        report.put("topK", TOP_K);
        report.put("results", results);

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);

        assertThat(results)
                .extracting(BenchmarkResult::recallAtK)
                .allSatisfy(recall -> assertThat(recall).isBetween(0.0, 1.0));
    }

    private static BenchmarkResult documentExactResult(
            List<Long> exact
    ) {
        String sql = """
                WITH candidates AS MATERIALIZED (
                    SELECT v.id, v.embedding
                    FROM bench_list v
                    WHERE v.access_level = ?
                      AND v.document_id = ?
                      AND v.generation = 1
                )
                SELECT id
                FROM candidates
                ORDER BY embedding <=> ?
                LIMIT ?
                """;

        String documentId = documentId(0);
        for (int ignored = 0; ignored < config.warmupIterations(); ignored++) {
            queryIds(
                    sql,
                    1L,
                    documentId,
                    new PGvector(queryVector),
                    TOP_K
            );
        }

        long[] nanos = new long[config.measureIterations()];
        List<Long> last = List.of();
        for (int i = 0; i < config.measureIterations(); i++) {
            long started = System.nanoTime();
            last = queryIds(
                    sql,
                    1L,
                    documentId,
                    new PGvector(queryVector),
                    TOP_K
            );
            nanos[i] = System.nanoTime() - started;
        }

        String planJson = explain(
                sql,
                1L,
                documentId,
                new PGvector(queryVector),
                TOP_K
        );
        PlanSummary plan = summarizePlan(planJson);

        return new BenchmarkResult(
                "DOCUMENT_EXACT_LIST",
                recallAtK(last, exact),
                percentileMillis(nanos, 0.50),
                percentileMillis(nanos, 0.95),
                percentileMillis(nanos, 0.99),
                plan.planningTimeMs(),
                plan.executionTimeMs(),
                plan.sharedHitBlocks(),
                plan.sharedReadBlocks(),
                plan.totalRowsVisited(),
                plan.executedRelationRows(),
                plan.executedRelations(),
                plan.indexNames(),
                planJson
        );
    }

    private static void createSchema() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");

        jdbc.execute("""
                CREATE TABLE bench_lifecycle (
                    document_id VARCHAR(100) PRIMARY KEY,
                    published_generation BIGINT NOT NULL,
                    access_level BIGINT NOT NULL,
                    retention_status VARCHAR(32) NOT NULL
                )
                """);

        jdbc.execute("""
                CREATE TABLE bench_vector_json (
                    id BIGINT PRIMARY KEY,
                    content TEXT NOT NULL,
                    metadata JSONB NOT NULL,
                    embedding VECTOR(%d) NOT NULL
                )
                """.formatted(config.dimensions()));

        jdbc.execute("""
                CREATE TABLE bench_typed_global (
                    id BIGINT PRIMARY KEY,
                    access_level BIGINT NOT NULL,
                    document_id VARCHAR(100) NOT NULL,
                    generation BIGINT NOT NULL,
                    embedding VECTOR(%d) NOT NULL
                )
                """.formatted(config.dimensions()));

        jdbc.execute("""
                CREATE TABLE bench_partial (
                    id BIGINT PRIMARY KEY,
                    access_level BIGINT NOT NULL,
                    document_id VARCHAR(100) NOT NULL,
                    generation BIGINT NOT NULL,
                    embedding VECTOR(%d) NOT NULL
                )
                """.formatted(config.dimensions()));

        jdbc.execute("""
                CREATE TABLE bench_list (
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
                    CREATE TABLE bench_list_al_%d
                    PARTITION OF bench_list
                    FOR VALUES IN (%d)
                    """.formatted(accessLevel, accessLevel));
        }
    }

    private static void seedCorpus() {
        int documentCount = Math.max(
                config.accessLevels(),
                (config.rows() + config.chunksPerDocument() - 1)
                        / config.chunksPerDocument()
        );

        jdbc.batchUpdate(
                """
                INSERT INTO bench_lifecycle (
                    document_id,
                    published_generation,
                    access_level,
                    retention_status
                ) VALUES (?, 1, ?, 'ACTIVE')
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(
                            PreparedStatement ps,
                            int index
                    ) throws java.sql.SQLException {
                        ps.setString(1, documentId(index));
                        ps.setLong(
                                2,
                                (index % config.accessLevels()) + 1L
                        );
                    }

                    @Override
                    public int getBatchSize() {
                        return documentCount;
                    }
                }
        );

        List<BenchRow> rows = new ArrayList<>(config.rows());
        for (int index = 0; index < config.rows(); index++) {
            long id = index + 1L;
            int documentIndex = index / config.chunksPerDocument();
            long accessLevel = (documentIndex % config.accessLevels()) + 1L;
            rows.add(new BenchRow(
                    id,
                    accessLevel,
                    documentId(documentIndex),
                    1L,
                    "chunk-" + index,
                    embedding(id, config.dimensions())
            ));
        }

        jdbc.batchUpdate(
                """
                INSERT INTO bench_vector_json (
                    id, content, metadata, embedding
                ) VALUES (?, ?, ?::jsonb, ?)
                """,
                rows,
                500,
                (ps, row) -> {
                    ps.setLong(1, row.id());
                    ps.setString(2, row.chunkId());
                    ps.setString(
                            3,
                            """
                            {"documentId":"%s","generation":%d,"chunkId":"%s"}
                            """.formatted(
                                    row.documentId(),
                                    row.generation(),
                                    row.chunkId()
                            ).strip()
                    );
                    ps.setObject(4, new PGvector(row.embedding()));
                }
        );

        insertTyped("bench_typed_global", rows);
        insertTyped("bench_partial", rows);
        insertTyped("bench_list", rows);
    }

    private static void insertTyped(
            String table,
            List<BenchRow> rows
    ) {
        jdbc.batchUpdate(
                """
                INSERT INTO %s (
                    id, access_level, document_id, generation, embedding
                ) VALUES (?, ?, ?, ?, ?)
                """.formatted(table),
                rows,
                500,
                (ps, row) -> {
                    ps.setLong(1, row.id());
                    ps.setLong(2, row.accessLevel());
                    ps.setString(3, row.documentId());
                    ps.setLong(4, row.generation());
                    ps.setObject(5, new PGvector(row.embedding()));
                }
        );
    }

    private static void createIndexes() {
        jdbc.execute("""
                CREATE INDEX bench_json_embedding_hnsw
                ON bench_vector_json
                USING HNSW (embedding vector_cosine_ops)
                """);

        jdbc.execute("""
                CREATE INDEX bench_typed_global_embedding_hnsw
                ON bench_typed_global
                USING HNSW (embedding vector_cosine_ops)
                """);

        jdbc.execute("""
                CREATE INDEX bench_typed_global_acl_document_generation
                ON bench_typed_global (
                    access_level,
                    document_id,
                    generation
                )
                """);

        jdbc.execute("""
                CREATE INDEX bench_partial_acl_document_generation
                ON bench_partial (
                    access_level,
                    document_id,
                    generation
                )
                """);

        for (int accessLevel = 1;
                accessLevel <= config.accessLevels();
                accessLevel++) {
            jdbc.execute("""
                    CREATE INDEX bench_partial_al_%d_hnsw
                    ON bench_partial
                    USING HNSW (embedding vector_cosine_ops)
                    WHERE access_level = %d
                    """.formatted(accessLevel, accessLevel));
        }

        jdbc.execute("""
                CREATE INDEX bench_list_embedding_hnsw
                ON bench_list
                USING HNSW (embedding vector_cosine_ops)
                """);

        jdbc.execute("""
                CREATE INDEX bench_list_document_generation
                ON bench_list (
                    document_id,
                    generation
                )
                """);
    }

    private static void analyze() {
        jdbc.execute("ANALYZE bench_lifecycle");
        jdbc.execute("ANALYZE bench_vector_json");
        jdbc.execute("ANALYZE bench_typed_global");
        jdbc.execute("ANALYZE bench_partial");
        jdbc.execute("ANALYZE bench_list");
    }

    private static QuerySpec querySpec(Layout layout) {
        return switch (layout) {
            case CURRENT_JSON_GLOBAL -> new QuerySpec("""
                    SELECT v.id
                    FROM bench_vector_json v
                    JOIN bench_lifecycle l
                      ON l.document_id = v.metadata->>'documentId'
                     AND l.published_generation =
                            (v.metadata->>'generation')::bigint
                    WHERE l.retention_status = 'ACTIVE'
                      AND l.access_level = ?
                    ORDER BY v.embedding <=> ?
                    LIMIT ?
                    """);
            case TYPED_GLOBAL -> new QuerySpec("""
                    SELECT v.id
                    FROM bench_typed_global v
                    JOIN bench_lifecycle l
                      ON l.document_id = v.document_id
                     AND l.published_generation = v.generation
                     AND l.access_level = v.access_level
                    WHERE v.access_level = ?
                      AND l.retention_status = 'ACTIVE'
                    ORDER BY v.embedding <=> ?
                    LIMIT ?
                    """);
            case TYPED_PARTIAL_HNSW -> new QuerySpec("""
                    SELECT v.id
                    FROM bench_partial v
                    JOIN bench_lifecycle l
                      ON l.document_id = v.document_id
                     AND l.published_generation = v.generation
                     AND l.access_level = v.access_level
                    WHERE v.access_level = ?
                      AND l.retention_status = 'ACTIVE'
                    ORDER BY v.embedding <=> ?
                    LIMIT ?
                    """);
            case LIST_LOCAL_HNSW -> new QuerySpec("""
                    SELECT v.id
                    FROM bench_list v
                    JOIN bench_lifecycle l
                      ON l.document_id = v.document_id
                     AND l.published_generation = v.generation
                     AND l.access_level = v.access_level
                    WHERE v.access_level = ?
                      AND l.retention_status = 'ACTIVE'
                    ORDER BY v.embedding <=> ?
                    LIMIT ?
                    """);
        };
    }

    private static List<Long> exactGroundTruth(
            long accessLevel,
            float[] vector,
            int limit
    ) {
        return queryIds(
                """
                WITH candidates AS MATERIALIZED (
                    SELECT id, embedding
                    FROM bench_list
                    WHERE access_level = ?
                )
                SELECT id
                FROM candidates
                ORDER BY embedding <=> ?
                LIMIT ?
                """,
                accessLevel,
                new PGvector(vector),
                limit
        );
    }

    private static List<Long> documentExactGroundTruth(
            long accessLevel,
            String documentId,
            float[] vector,
            int limit
    ) {
        return queryIds(
                """
                WITH candidates AS MATERIALIZED (
                    SELECT id, embedding
                    FROM bench_list
                    WHERE access_level = ?
                      AND document_id = ?
                      AND generation = 1
                )
                SELECT id
                FROM candidates
                ORDER BY embedding <=> ?
                LIMIT ?
                """,
                accessLevel,
                documentId,
                new PGvector(vector),
                limit
        );
    }

    private static List<Long> queryIds(
            String sql,
            Object... parameters
    ) {
        try {
            setRetrievalSession(benchmarkConnection);
            try (PreparedStatement ps =
                    benchmarkConnection.prepareStatement(sql)) {
                bind(ps, parameters);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Long> ids = new ArrayList<>();
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                    return List.copyOf(ids);
                }
            } finally {
                resetRetrievalSession(benchmarkConnection);
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(
                    "Benchmark query failed",
                    exception
            );
        }
    }

    private static String explain(
            String sql,
            Object... parameters
    ) {
        try {
            setRetrievalSession(benchmarkConnection);
            try (PreparedStatement ps = benchmarkConnection.prepareStatement(
                    """
                    EXPLAIN (
                        ANALYZE,
                        BUFFERS,
                        SETTINGS,
                        SUMMARY,
                        FORMAT JSON
                    )
                    """ + sql
            )) {
                bind(ps, parameters);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            } finally {
                resetRetrievalSession(benchmarkConnection);
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(
                    "Benchmark EXPLAIN failed",
                    exception
            );
        }
    }

    private static void bind(
            PreparedStatement ps,
            Object... parameters
    ) throws java.sql.SQLException {
        for (int index = 0; index < parameters.length; index++) {
            ps.setObject(index + 1, parameters[index]);
        }
    }

    private static void setRetrievalSession(
            Connection connection
    ) throws java.sql.SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET hnsw.iterative_scan = strict_order");
        }
    }

    private static void resetRetrievalSession(
            Connection connection
    ) throws java.sql.SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET hnsw.iterative_scan");
        }
    }

    private static String genericListPlan(
            long accessLevel,
            float[] vector,
            int limit
    ) {
        try {
            Connection connection = benchmarkConnection;
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET hnsw.iterative_scan = strict_order");
                statement.execute("SET plan_cache_mode = force_generic_plan");
                statement.execute("SET enable_seqscan = off");
                statement.execute(
                        """
                        PREPARE akmai_list_generic (
                            bigint,
                            vector,
                            integer
                        ) AS
                        SELECT id
                        FROM bench_list
                        WHERE access_level = $1
                        ORDER BY embedding <=> $2
                        LIMIT $3
                        """
                );

                String execute = """
                        EXPLAIN (
                            ANALYZE,
                            BUFFERS,
                            SETTINGS,
                            SUMMARY,
                            FORMAT JSON
                        )
                        EXECUTE akmai_list_generic(
                            %d,
                            '%s'::vector,
                            %d
                        )
                        """.formatted(
                                accessLevel,
                                vectorLiteral(vector),
                                limit
                        );

                try (ResultSet rs = statement.executeQuery(execute)) {
                    rs.next();
                    return rs.getString(1);
                } finally {
                    statement.execute("DEALLOCATE akmai_list_generic");
                    statement.execute("RESET enable_seqscan");
                    statement.execute("RESET plan_cache_mode");
                    statement.execute("RESET hnsw.iterative_scan");
                }
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(
                    "Generic LIST plan failed",
                    exception
            );
        }
    }

    private static PlanSummary summarizePlan(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode top = root.get(0);

            LinkedHashSet<String> executedRelations = new LinkedHashSet<>();
            LinkedHashSet<String> executedIndexRelations = new LinkedHashSet<>();
            LinkedHashSet<String> indexNames = new LinkedHashSet<>();
            Map<String, Long> executedRelationRows = new LinkedHashMap<>();
            long[] rows = new long[1];

            walkPlan(
                    top.path("Plan"),
                    executedRelations,
                    executedIndexRelations,
                    indexNames,
                    executedRelationRows,
                    rows
            );

            JsonNode rootPlan = top.path("Plan");
            return new PlanSummary(
                    top.path("Planning Time").asDouble(),
                    top.path("Execution Time").asDouble(),
                    rootPlan.path("Shared Hit Blocks").asLong(),
                    rootPlan.path("Shared Read Blocks").asLong(),
                    rows[0],
                    Set.copyOf(executedRelations),
                    Set.copyOf(executedIndexRelations),
                    Set.copyOf(indexNames),
                    Map.copyOf(executedRelationRows)
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot parse EXPLAIN JSON",
                    exception
            );
        }
    }

    private static void walkPlan(
            JsonNode node,
            Set<String> executedRelations,
            Set<String> executedIndexRelations,
            Set<String> indexNames,
            Map<String, Long> executedRelationRows,
            long[] rows
    ) {
        if (node == null || node.isMissingNode()) {
            return;
        }

        long loops = node.path("Actual Loops").asLong(1L);
        boolean executed = loops > 0;

        if (executed) {
            String relation = node.path("Relation Name").asText("");
            if (!relation.isBlank()) {
                executedRelations.add(relation);
                long relationRows =
                        node.path("Actual Rows").asLong() * loops;
                executedRelationRows.merge(
                        relation,
                        relationRows,
                        Math::max
                );
            }

            String nodeType = node.path("Node Type").asText("");
            if (!relation.isBlank()
                    && nodeType.toLowerCase(Locale.ROOT).contains("index")) {
                executedIndexRelations.add(relation);
            }

            String indexName = node.path("Index Name").asText("");
            if (!indexName.isBlank()) {
                indexNames.add(indexName);
            }

            rows[0] += node.path("Actual Rows").asLong() * loops;
        }

        JsonNode plans = node.path("Plans");
        if (plans.isArray()) {
            for (JsonNode child : plans) {
                walkPlan(
                        child,
                        executedRelations,
                        executedIndexRelations,
                        indexNames,
                        executedRelationRows,
                        rows
                );
            }
        }
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
        return sorted[index] / (double) TimeUnit.MILLISECONDS.toNanos(1);
    }

    private static String vectorLiteral(float[] values) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(Float.toString(values[index]));
        }
        return result.append(']').toString();
    }

    private static float[] embedding(long seed, int dimensions) {
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

    private static String documentId(int index) {
        return "doc-" + String.format(Locale.ROOT, "%06d", index);
    }

    private static long accessLevelForRow(long zeroBasedRow) {
        long documentIndex = zeroBasedRow / config.chunksPerDocument();
        return (documentIndex % config.accessLevels()) + 1L;
    }

    private enum Layout {
        CURRENT_JSON_GLOBAL,
        TYPED_GLOBAL,
        TYPED_PARTIAL_HNSW,
        LIST_LOCAL_HNSW
    }

    private record QuerySpec(String sql) {
    }

    private record BenchRow(
            long id,
            long accessLevel,
            String documentId,
            long generation,
            String chunkId,
            float[] embedding
    ) {
        private BenchRow {
            embedding = embedding.clone();
        }

        @Override
        public float[] embedding() {
            return embedding.clone();
        }
    }

    private record PlanSummary(
            double planningTimeMs,
            double executionTimeMs,
            long sharedHitBlocks,
            long sharedReadBlocks,
            long totalRowsVisited,
            Set<String> executedRelations,
            Set<String> executedIndexRelations,
            Set<String> indexNames,
            Map<String, Long> executedRelationRows
    ) {
    }

    private record BenchmarkResult(
            String layout,
            double recallAtK,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double explainPlanningMs,
            double explainExecutionMs,
            long sharedHitBlocks,
            long sharedReadBlocks,
            long totalRowsVisited,
            Map<String, Long> executedRelationRows,
            Set<String> executedRelations,
            Set<String> indexNames,
            String explainJson
    ) {
    }

    private record BenchmarkConfig(
            int rows,
            int dimensions,
            int accessLevels,
            int chunksPerDocument,
            int warmupIterations,
            int measureIterations
    ) {
        private static BenchmarkConfig fromEnvironment() {
            boolean full = enabled(
                    System.getenv("AKMAI_RUN_RETRIEVAL_BENCHMARK")
            );
            return new BenchmarkConfig(
                    intValue(
                            "AKMAI_BENCHMARK_ROWS",
                            full ? 100_000 : CI_ROWS
                    ),
                    intValue(
                            "AKMAI_BENCHMARK_DIMENSIONS",
                            full ? 64 : CI_DIMENSIONS
                    ),
                    intValue(
                            "AKMAI_BENCHMARK_ACCESS_LEVELS",
                            full ? 8 : CI_ACCESS_LEVELS
                    ),
                    intValue(
                            "AKMAI_BENCHMARK_CHUNKS_PER_DOCUMENT",
                            full ? 300 : CI_CHUNKS_PER_DOCUMENT
                    ),
                    intValue(
                            "AKMAI_BENCHMARK_WARMUPS",
                            full ? 5 : 1
                    ),
                    intValue(
                            "AKMAI_BENCHMARK_ITERATIONS",
                            full ? 20 : 1
                    )
            );
        }

        private static boolean enabled(String value) {
            return value != null
                    && Set.of("true", "1", "yes")
                    .contains(value.toLowerCase(Locale.ROOT));
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
    }
}
