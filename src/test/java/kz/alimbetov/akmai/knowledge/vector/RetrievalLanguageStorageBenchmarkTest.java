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
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
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
        named = "AKMAI_RUN_LANGUAGE_STORAGE_BENCHMARK",
        matches = "(?i)true|1|yes"
)
class RetrievalLanguageStorageBenchmarkTest {

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
            ).withDatabaseName("akmai_language_bench")
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
        jdbc.execute("ANALYZE bench_lang");
    }

    @AfterAll
    static void close() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void benchmarkLanguageAndStoragePruning() throws Exception {
        Query sameLanguage = annQuery("en");
        Query crossLanguage = annQuery(null);

        List<Long> sameExact = exactGroundTruth("en");
        List<Long> crossExact = exactGroundTruth(null);

        Measurement same = measure(
                "SAME_LANGUAGE_ACTIVE",
                sameLanguage,
                sameExact
        );
        Measurement cross = measure(
                "CROSS_LANGUAGE_ACTIVE",
                crossLanguage,
                crossExact
        );

        assertThat(same.recallAtK()).isGreaterThanOrEqualTo(0.8);
        assertThat(cross.recallAtK()).isGreaterThanOrEqualTo(0.8);

        assertThat(same.plan().relations())
                .contains("bench_lang_al_1_lang_en_s0")
                .noneMatch(name -> name.endsWith("_s1"));
        assertThat(same.plan().relations().stream()
                .filter(name -> name.startsWith("bench_lang_al_1_lang_"))
                .toList())
                .containsExactly("bench_lang_al_1_lang_en_s0");

        assertThat(cross.plan().relations())
                .noneMatch(name -> name.endsWith("_s1"));
        assertThat(cross.plan().relations().stream()
                .filter(name -> name.startsWith("bench_lang_al_1_lang_"))
                .count())
                .isGreaterThan(1);

        assertThat(same.plan().indexes())
                .anyMatch(name -> name.contains("hnsw"));
        assertThat(cross.plan().indexes())
                .anyMatch(name -> name.contains("hnsw"));

        Path output = Path.of(
                "target",
                "retrieval-benchmark",
                "language-storage.json"
        );
        Files.createDirectories(output.getParent());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("postgresImage", "pgvector/pgvector:pg17");
        report.put("dimensions", config.dimensions());
        report.put("languages", LANGUAGES);
        report.put("activeRowsPerLanguage", config.activeRowsPerLanguage());
        report.put("archivedRowsPerLanguage", config.archivedRowsPerLanguage());
        report.put("topK", TOP_K);
        report.put("sameLanguage", same);
        report.put("crossLanguage", cross);
        report.put(
                "sameVsCrossP50Ratio",
                cross.p50Ms() == 0.0 ? 0.0 : same.p50Ms() / cross.p50Ms()
        );

        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(output.toFile(), report);
    }

    private static void createSchema() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");

        jdbc.execute("""
                CREATE TABLE bench_lang (
                    access_level BIGINT NOT NULL,
                    language VARCHAR(16) NOT NULL,
                    storage_state SMALLINT NOT NULL,
                    id BIGINT NOT NULL,
                    document_id VARCHAR(100) NOT NULL,
                    generation BIGINT NOT NULL,
                    embedding VECTOR(%d) NOT NULL,
                    PRIMARY KEY (
                        access_level,
                        language,
                        storage_state,
                        id
                    )
                )
                PARTITION BY LIST (access_level)
                """.formatted(config.dimensions()));

        jdbc.execute("""
                CREATE TABLE bench_lang_al_1
                PARTITION OF bench_lang
                FOR VALUES IN (1)
                PARTITION BY LIST (language)
                """);

        for (String language : LANGUAGES) {
            String languageParent = languageParent(language);
            jdbc.execute("""
                    CREATE TABLE %s
                    PARTITION OF bench_lang_al_1
                    FOR VALUES IN ('%s')
                    PARTITION BY LIST (storage_state)
                    """.formatted(languageParent, language));

            jdbc.execute("""
                    CREATE TABLE %s_s0
                    PARTITION OF %s
                    FOR VALUES IN (0)
                    """.formatted(languageParent, languageParent));

            jdbc.execute("""
                    CREATE TABLE %s_s1
                    PARTITION OF %s
                    FOR VALUES IN (1)
                    """.formatted(languageParent, languageParent));
        }
    }

    private static void seed() {
        long id = 1L;
        List<Row> batch = new ArrayList<>(250);

        for (int languageIndex = 0;
                languageIndex < LANGUAGES.size();
                languageIndex++) {
            String language = LANGUAGES.get(languageIndex);

            for (int row = 0;
                    row < config.activeRowsPerLanguage();
                    row++) {
                batch.add(new Row(
                        id,
                        language,
                        (short) 0,
                        "active-" + language + "-" + (row / 300),
                        embedding(id, config.dimensions())
                ));
                id++;
                if (batch.size() == 250) {
                    insertBatch(batch);
                    batch.clear();
                }
            }

            for (int row = 0;
                    row < config.archivedRowsPerLanguage();
                    row++) {
                batch.add(new Row(
                        id,
                        language,
                        (short) 1,
                        "archive-" + language + "-" + (row / 300),
                        embedding(id, config.dimensions())
                ));
                id++;
                if (batch.size() == 250) {
                    insertBatch(batch);
                    batch.clear();
                }
            }
        }

        if (!batch.isEmpty()) {
            insertBatch(batch);
        }
    }

    private static void insertBatch(List<Row> rows) {
        jdbc.batchUpdate(
                """
                INSERT INTO bench_lang (
                    access_level,
                    language,
                    storage_state,
                    id,
                    document_id,
                    generation,
                    embedding
                ) VALUES (1, ?, ?, ?, ?, 1, ?)
                """,
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setString(1, row.language());
                    ps.setShort(2, row.storageState());
                    ps.setLong(3, row.id());
                    ps.setString(4, row.documentId());
                    ps.setObject(5, new PGvector(row.embedding()));
                }
        );
    }

    private static void createIndexes() {
        for (String language : LANGUAGES) {
            String active = languageParent(language) + "_s0";
            String archive = languageParent(language) + "_s1";

            jdbc.execute("""
                    CREATE INDEX %s_hnsw
                    ON %s
                    USING HNSW (embedding vector_cosine_ops)
                    """.formatted(active, active));
            jdbc.execute("""
                    CREATE INDEX %s_doc
                    ON %s (document_id, generation)
                    """.formatted(active, active));
            jdbc.execute("""
                    CREATE INDEX %s_doc
                    ON %s (document_id, generation)
                    """.formatted(archive, archive));
        }
    }

    private static Query annQuery(String language) {
        StringBuilder sql = new StringBuilder("""
                SELECT id
                FROM bench_lang
                WHERE access_level = ?
                  AND storage_state = 0
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(1L);

        if (language != null) {
            sql.append("  AND language = ?\n");
            parameters.add(language);
        }

        sql.append("""
                ORDER BY embedding <=> ?
                LIMIT ?
                """);
        parameters.add(new PGvector(queryVector));
        parameters.add(TOP_K);

        return new Query(sql.toString(), List.copyOf(parameters));
    }

    private static List<Long> exactGroundTruth(String language) {
        StringBuilder sql = new StringBuilder("""
                WITH candidates AS MATERIALIZED (
                    SELECT id, embedding
                    FROM bench_lang
                    WHERE access_level = 1
                      AND storage_state = 0
                """);
        List<Object> parameters = new ArrayList<>();

        if (language != null) {
            sql.append("      AND language = ?\n");
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
    ) throws Exception {
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

        Plan plan = summarize(explain(query));
        return new Measurement(
                name,
                recallAtK(last, expected),
                percentileMs(nanos, 0.50),
                percentileMs(nanos, 0.95),
                percentileMs(nanos, 0.99),
                plan
        );
    }

    private static List<Long> queryIds(Query query) {
        try {
            setSession();
            try (PreparedStatement ps = connection.prepareStatement(query.sql())) {
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
                    "Language storage benchmark query failed",
                    exception
            );
        }
    }

    private static String explain(Query query) {
        try {
            setSession();
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
                resetSession();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Language storage benchmark EXPLAIN failed",
                    exception
            );
        }
    }

    private static void setSession() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET hnsw.iterative_scan = strict_order");
            statement.execute("SET hnsw.ef_search = 80");
        }
    }

    private static void resetSession() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET hnsw.ef_search");
            statement.execute("RESET hnsw.iterative_scan");
        }
    }

    private static void bind(
            PreparedStatement ps,
            List<Object> parameters
    ) throws Exception {
        for (int i = 0; i < parameters.size(); i++) {
            ps.setObject(i + 1, parameters.get(i));
        }
    }

    private static Plan summarize(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode top = root.get(0);
            Set<String> relations = new LinkedHashSet<>();
            Set<String> indexes = new LinkedHashSet<>();
            walk(top.path("Plan"), relations, indexes);

            return new Plan(
                    top.path("Planning Time").asDouble(),
                    top.path("Execution Time").asDouble(),
                    top.path("Plan").path("Shared Hit Blocks").asLong(),
                    top.path("Plan").path("Shared Read Blocks").asLong(),
                    Set.copyOf(relations),
                    Set.copyOf(indexes)
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot parse language benchmark plan",
                    exception
            );
        }
    }

    private static void walk(
            JsonNode node,
            Set<String> relations,
            Set<String> indexes
    ) {
        if (node == null || node.isMissingNode()) {
            return;
        }

        if (node.path("Actual Loops").asLong(1L) > 0) {
            String relation = node.path("Relation Name").asText("");
            String index = node.path("Index Name").asText("");
            if (!relation.isBlank()) {
                relations.add(relation);
            }
            if (!index.isBlank()) {
                indexes.add(index);
            }
        }

        JsonNode plans = node.path("Plans");
        if (plans.isArray()) {
            for (JsonNode child : plans) {
                walk(child, relations, indexes);
            }
        }
    }

    private static double recallAtK(
            List<Long> actual,
            List<Long> expected
    ) {
        Set<Long> expectedSet = new LinkedHashSet<>(expected);
        long matches = actual.stream()
                .distinct()
                .filter(expectedSet::contains)
                .count();
        return expectedSet.isEmpty()
                ? 1.0
                : (double) matches / expectedSet.size();
    }

    private static double percentileMs(long[] values, double percentile) {
        long[] sorted = values.clone();
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

    private static float[] embedding(long seed, int dimensions) {
        SplittableRandom random = new SplittableRandom(
                0x9E3779B97F4A7C15L ^ seed
        );
        float[] values = new float[dimensions];
        double norm = 0.0;

        for (int i = 0; i < dimensions; i++) {
            double value = random.nextDouble(-1.0, 1.0);
            values[i] = (float) value;
            norm += value * value;
        }

        double scale = Math.sqrt(norm);
        for (int i = 0; i < dimensions; i++) {
            values[i] = (float) (values[i] / scale);
        }
        return values;
    }

    private static String languageParent(String language) {
        return "bench_lang_al_1_lang_" + language;
    }

    private record Row(
            long id,
            String language,
            short storageState,
            String documentId,
            float[] embedding
    ) {
        private Row {
            embedding = embedding.clone();
        }

        @Override
        public float[] embedding() {
            return embedding.clone();
        }
    }

    private record Query(
            String sql,
            List<Object> parameters
    ) {
    }

    private record Plan(
            double planningTimeMs,
            double executionTimeMs,
            long sharedHitBlocks,
            long sharedReadBlocks,
            Set<String> relations,
            Set<String> indexes
    ) {
    }

    private record Measurement(
            String name,
            double recallAtK,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            Plan plan
    ) {
    }

    private record Config(
            int dimensions,
            int activeRowsPerLanguage,
            int archivedRowsPerLanguage,
            int warmups,
            int iterations
    ) {
        private static Config fromEnvironment() {
            return new Config(
                    value("AKMAI_LANGUAGE_BENCH_DIMENSIONS", 1024),
                    value("AKMAI_LANGUAGE_BENCH_ACTIVE_ROWS", 800),
                    value("AKMAI_LANGUAGE_BENCH_ARCHIVED_ROWS", 100),
                    value("AKMAI_LANGUAGE_BENCH_WARMUPS", 3),
                    value("AKMAI_LANGUAGE_BENCH_ITERATIONS", 10)
            );
        }

        private static int value(String name, int fallback) {
            String raw = System.getenv(name);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            int parsed = Integer.parseInt(raw);
            if (parsed <= 0) {
                throw new IllegalArgumentException(
                        name + " must be positive"
                );
            }
            return parsed;
        }
    }
}
