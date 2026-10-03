package kz.alimbetov.akmai.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
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
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
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
        named = "AKMAI_RUN_LANGUAGE_HOT_BENCHMARK",
        matches = "(?i)true|1|yes"
)
class RetrievalLanguageHotBenchmarkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TOP_K = 10;
    private static final List<String> LANGUAGES = List.of(
            "kk", "ru", "en", "zh", "de", "fr",
            "es", "pt", "it", "tr", "el", "unknown"
    );

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres")
            ).withDatabaseName("akmai_language_hot_bench")
                    .withUsername("akmai")
                    .withPassword("akmai");

    private static JdbcTemplate jdbc;
    private static Connection connection;
    private static Config config;
    private static float[] queryVector;

    @BeforeAll
    static void setup() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        jdbc = new JdbcTemplate(dataSource);
        connection = dataSource.getConnection();
        config = Config.fromEnvironment();
        queryVector = embedding(1L, config.dimensions());

        createSchema();
        seed();
        createIndexes();
        jdbc.execute("ANALYZE bench_hot_lifecycle");
        jdbc.execute("ANALYZE bench_hot");
    }

    @AfterAll
    static void close() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void benchmarkLanguagePruningOnHotOnlyLayout() throws Exception {
        Query sameLanguage = annQuery("en");
        Query crossLanguage = annQuery(null);

        List<Long> sameExact = exactGroundTruth("en");
        List<Long> crossExact = exactGroundTruth(null);

        Measurement same = measure(
                "SAME_LANGUAGE_HOT",
                sameLanguage,
                sameExact
        );
        Measurement cross = measure(
                "CROSS_LANGUAGE_HOT",
                crossLanguage,
                crossExact
        );

        assertThat(same.recallAtK()).isGreaterThanOrEqualTo(0.8);
        assertThat(cross.recallAtK()).isGreaterThanOrEqualTo(0.8);

        String samePlan = explain(sameLanguage, false);
        assertThat(samePlan)
                .contains("bench_hot_al_1_lang_en")
                .doesNotContain("bench_hot_al_1_lang_ru")
                .doesNotContain("_s0")
                .doesNotContain("_s1");

        String crossPlan = explain(crossLanguage, false);
        long touchedLanguages = LANGUAGES.stream()
                .filter(language -> crossPlan.contains(
                        "bench_hot_al_1_lang_" + language
                ))
                .count();
        assertThat(touchedLanguages).isGreaterThan(1);

        String hnswPlan = explain(sameLanguage, true);
        assertThat(hnswPlan.toLowerCase()).contains("hnsw");

        Path output = Path.of(
                "target",
                "retrieval-benchmark",
                "language-hot.json"
        );
        Files.createDirectories(output.getParent());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("postgresImage", "pgvector/pgvector:pg17");
        report.put("layout", "ACL_LANGUAGE_HOT_ONLY");
        report.put("dimensions", config.dimensions());
        report.put("languages", LANGUAGES);
        report.put("rowsPerLanguage", config.rowsPerLanguage());
        report.put("topK", TOP_K);
        report.put("sameLanguage", same);
        report.put("crossLanguage", cross);
        report.put(
                "sameVsCrossP99Ratio",
                cross.p99Ms() == 0.0
                        ? 0.0
                        : same.p99Ms() / cross.p99Ms()
        );

        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(output.toFile(), report);
    }

    private static void createSchema() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("""
                CREATE TABLE bench_hot_lifecycle (
                    document_id VARCHAR(100) PRIMARY KEY,
                    published_generation BIGINT NOT NULL,
                    access_level BIGINT NOT NULL,
                    retention_status VARCHAR(32) NOT NULL
                )
                """);

        jdbc.execute("""
                CREATE TABLE bench_hot (
                    access_level BIGINT NOT NULL,
                    language VARCHAR(16) NOT NULL,
                    id BIGINT NOT NULL,
                    document_id VARCHAR(100) NOT NULL,
                    generation BIGINT NOT NULL,
                    embedding VECTOR(%d) NOT NULL,
                    PRIMARY KEY (
                        access_level,
                        language,
                        id
                    )
                )
                PARTITION BY LIST (access_level)
                """.formatted(config.dimensions()));

        jdbc.execute("""
                CREATE TABLE bench_hot_al_1
                PARTITION OF bench_hot
                FOR VALUES IN (1)
                PARTITION BY LIST (language)
                """);

        for (String language : LANGUAGES) {
            jdbc.execute("""
                    CREATE TABLE bench_hot_al_1_lang_%s
                    PARTITION OF bench_hot_al_1
                    FOR VALUES IN ('%s')
                    """.formatted(language, language));
        }
    }

    private static void seed() {
        long id = 1L;
        List<Row> batch = new ArrayList<>(250);

        for (String language : LANGUAGES) {
            Set<String> documents = new LinkedHashSet<>();
            for (int row = 0; row < config.rowsPerLanguage(); row++) {
                String documentId =
                        "hot-" + language + "-" + (row / 300);
                documents.add(documentId);
                batch.add(new Row(
                        id,
                        language,
                        documentId,
                        embedding(id, config.dimensions())
                ));
                id++;
                if (batch.size() == 250) {
                    insertBatch(batch);
                    batch.clear();
                }
            }

            for (String documentId : documents) {
                jdbc.update(
                        """
                        INSERT INTO bench_hot_lifecycle (
                            document_id,
                            published_generation,
                            access_level,
                            retention_status
                        ) VALUES (?, 1, 1, 'ACTIVE')
                        """,
                        documentId
                );
            }
        }

        if (!batch.isEmpty()) {
            insertBatch(batch);
        }
    }

    private static void insertBatch(List<Row> rows) {
        jdbc.batchUpdate(
                """
                INSERT INTO bench_hot (
                    access_level,
                    language,
                    id,
                    document_id,
                    generation,
                    embedding
                ) VALUES (1, ?, ?, ?, 1, ?)
                """,
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setString(1, row.language());
                    ps.setLong(2, row.id());
                    ps.setString(3, row.documentId());
                    ps.setObject(4, new PGvector(row.embedding()));
                }
        );
    }

    private static void createIndexes() {
        for (String language : LANGUAGES) {
            String leaf = "bench_hot_al_1_lang_" + language;
            jdbc.execute("""
                    CREATE INDEX %s_hnsw
                    ON %s
                    USING HNSW (embedding vector_cosine_ops)
                    """.formatted(leaf, leaf));
            jdbc.execute("""
                    CREATE INDEX %s_doc
                    ON %s (document_id, generation)
                    """.formatted(leaf, leaf));
        }
    }

    private static Query annQuery(String language) {
        List<String> languages = language == null
                ? LANGUAGES
                : List.of(language);

        StringBuilder sql = new StringBuilder("""
                SELECT candidate.id
                FROM (
                """);
        List<Object> parameters = new ArrayList<>();

        for (int index = 0; index < languages.size(); index++) {
            if (index > 0) {
                sql.append("\nUNION ALL\n");
            }

            sql.append("""
                    (
                        SELECT
                            id,
                            embedding <=> ? AS distance
                        FROM bench_hot v
                        WHERE v.access_level = ?
                          AND v.language = ?
                        ORDER BY v.embedding <=> ?
                        LIMIT ?
                    )
                    """);
            parameters.add(new PGvector(queryVector));
            parameters.add(1L);
            parameters.add(languages.get(index));
            parameters.add(new PGvector(queryVector));
            parameters.add(TOP_K);
        }

        sql.append("""
                ) candidate
                JOIN bench_hot_lifecycle l
                  ON l.document_id = candidate.document_id
                 AND l.published_generation = candidate.generation
                 AND l.access_level = 1
                WHERE l.retention_status = 'ACTIVE'
                ORDER BY candidate.distance
                LIMIT ?
                """);
        parameters.add(TOP_K);

        return new Query(sql.toString(), List.copyOf(parameters));
    }

    private static List<Long> exactGroundTruth(String language) {
        StringBuilder sql = new StringBuilder("""
                WITH candidates AS MATERIALIZED (
                    SELECT v.id, v.embedding
                    FROM bench_hot v
                    JOIN bench_hot_lifecycle l
                      ON l.document_id = v.document_id
                     AND l.published_generation = v.generation
                     AND l.access_level = v.access_level
                    WHERE v.access_level = 1
                      AND l.retention_status = 'ACTIVE'
                """);
        List<Object> parameters = new ArrayList<>();

        if (language != null) {
            sql.append("      AND v.language = ?\n");
            parameters.add(language);
        }

        sql.append("""
                )
                SELECT id
                FROM candidates
                ORDER BY embedding <=> ?
                LIMIT ?
                """);
        parameters.add(new PGvector(queryVector));
        parameters.add(TOP_K);

        return queryIds(new Query(sql.toString(), List.copyOf(parameters)));
    }

    private static Measurement measure(
            String name,
            Query query,
            List<Long> expected
    ) {
        for (int i = 0; i < config.warmups(); i++) {
            queryIds(query);
        }

        long[] nanos = new long[config.iterations()];
        List<Long> last = List.of();
        for (int i = 0; i < config.iterations(); i++) {
            long started = System.nanoTime();
            last = queryIds(query);
            nanos[i] = System.nanoTime() - started;
        }

        return new Measurement(
                name,
                recallAtK(last, expected),
                percentileMs(nanos, 0.50),
                percentileMs(nanos, 0.95),
                percentileMs(nanos, 0.99)
        );
    }

    private static List<Long> queryIds(Query query) {
        try {
            setSession();
            try (PreparedStatement ps =
                    connection.prepareStatement(query.sql())) {
                bind(ps, query.parameters());
                try (ResultSet rs = ps.executeQuery()) {
                    List<Long> ids = new ArrayList<>();
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                    return List.copyOf(ids);
                }
            } finally {
                resetSession();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "HOT language benchmark query failed",
                    exception
            );
        }
    }

    private static String explain(Query query, boolean disableSeqScan) {
        try {
            setSession();
            if (disableSeqScan) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET enable_seqscan = off");
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) " + query.sql()
            )) {
                bind(ps, query.parameters());
                try (ResultSet rs = ps.executeQuery()) {
                    StringBuilder plan = new StringBuilder();
                    while (rs.next()) {
                        if (!plan.isEmpty()) {
                            plan.append('\n');
                        }
                        plan.append(rs.getString(1));
                    }
                    return plan.toString();
                }
            } finally {
                if (disableSeqScan) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("RESET enable_seqscan");
                    }
                }
                resetSession();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "HOT language benchmark EXPLAIN failed",
                    exception
            );
        }
    }

    private static void setSession() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET hnsw.iterative_scan = strict_order");
            statement.execute("SET hnsw.ef_search = 40");
        }
    }

    private static void resetSession() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET hnsw.iterative_scan");
            statement.execute("RESET hnsw.ef_search");
        }
    }

    private static void bind(
            PreparedStatement ps,
            List<Object> parameters
    ) throws Exception {
        for (int index = 0; index < parameters.size(); index++) {
            ps.setObject(index + 1, parameters.get(index));
        }
    }

    private static double recallAtK(
            List<Long> actual,
            List<Long> expected
    ) {
        Set<Long> expectedSet = new LinkedHashSet<>(expected);
        long hits = actual.stream().filter(expectedSet::contains).count();
        return expectedSet.isEmpty()
                ? 1.0
                : (double) hits / expectedSet.size();
    }

    private static double percentileMs(long[] values, double percentile) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        index = Math.max(0, Math.min(index, sorted.length - 1));
        return sorted[index] / 1_000_000.0;
    }

    private static float[] embedding(long seed, int dimensions) {
        SplittableRandom random = new SplittableRandom(seed);
        float[] values = new float[dimensions];
        double sum = 0.0;
        for (int index = 0; index < dimensions; index++) {
            float value = (float) (random.nextDouble() * 2.0 - 1.0);
            values[index] = value;
            sum += value * value;
        }
        double norm = Math.sqrt(sum);
        for (int index = 0; index < dimensions; index++) {
            values[index] /= (float) norm;
        }
        return values;
    }

    private record Row(
            long id,
            String language,
            String documentId,
            float[] embedding
    ) {
    }

    private record Query(String sql, List<Object> parameters) {
    }

    private record Measurement(
            String name,
            double recallAtK,
            double p50Ms,
            double p95Ms,
            double p99Ms
    ) {
    }

    private record Config(
            int dimensions,
            int rowsPerLanguage,
            int warmups,
            int iterations
    ) {
        static Config fromEnvironment() {
            return new Config(
                    envInt("AKMAI_LANGUAGE_BENCH_DIMENSIONS", 1024),
                    envInt("AKMAI_LANGUAGE_BENCH_ROWS", 800),
                    envInt("AKMAI_LANGUAGE_BENCH_WARMUPS", 2),
                    envInt("AKMAI_LANGUAGE_BENCH_ITERATIONS", 6)
            );
        }

        private static int envInt(String name, int fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank()
                    ? fallback
                    : Integer.parseInt(value);
        }
    }
}
