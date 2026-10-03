package kz.alimbetov.akmai.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        named = "AKMAI_RUN_HOT_PURGE_BENCHMARK",
        matches = "(?i)true|1|yes"
)
class RetrievalHotPurgeDecisionBenchmarkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> LANGUAGES = List.of("en", "ru", "kk");
    private static final int TOP_K = 10;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres")
            ).withDatabaseName("akmai_hot_purge_bench")
                    .withUsername("akmai")
                    .withPassword("akmai");

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static Config config;
    private static float[] queryVector;
    private static ExecutorService executor;

    @BeforeAll
    static void setup() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        jdbc = new JdbcTemplate(dataSource);
        config = Config.fromEnvironment();
        queryVector = embedding(
                config.documents() * (long) config.chunksPerDocument(),
                config.dimensions()
        );
        executor = Executors.newFixedThreadPool(2);

        createSchema();
        seed();
        createIndexes();
        analyzeAll();
    }

    @AfterAll
    static void close() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void compareArchivedMoveDeleteWithSingleHotPurge() throws Exception {
        warmup(Layout.A);
        warmup(Layout.D);

        Measurement a = measure(Layout.A);
        Measurement d = measure(Layout.D);

        assertThat(d.leafCount()).isLessThan(a.leafCount());
        assertThat(d.logicalPayloadMutations())
                .isLessThan(a.logicalPayloadMutations());
        assertThat(d.walBytes()).isLessThan(a.walBytes());

        Path output = Path.of(
                "target",
                "retrieval-benchmark",
                "hot-purge-decision.json"
        );
        Files.createDirectories(output.getParent());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("postgresImage", "pgvector/pgvector:pg17");
        report.put("dimensions", config.dimensions());
        report.put("documents", config.documents());
        report.put("purgeDocuments", config.purgeDocuments());
        report.put("chunksPerDocument", config.chunksPerDocument());
        report.put("languages", LANGUAGES);
        report.put("baselineA", a);
        report.put("hotOnlyD", d);
        report.put(
                "walRatioDToA",
                a.walBytes() == 0
                        ? 0.0
                        : (double) d.walBytes() / a.walBytes()
        );
        report.put(
                "cleanupThroughputRatioDToA",
                a.cleanupDocumentsPerSecond() == 0.0
                        ? 0.0
                        : d.cleanupDocumentsPerSecond()
                                / a.cleanupDocumentsPerSecond()
        );
        report.put(
                "concurrentRetrievalP99RatioDToA",
                a.retrievalP99Ms() == 0.0
                        ? 0.0
                        : d.retrievalP99Ms() / a.retrievalP99Ms()
        );

        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(output.toFile(), report);
    }

    private static Measurement measure(Layout layout) throws Exception {
        checkpointAndFlushStats();
        long walBefore = walPosition();

        CountDownLatch start = new CountDownLatch(1);
        Future<CleanupMeasurement> cleanup = executor.submit(() -> {
            start.await();
            return cleanup(layout);
        });

        long[] retrievalNanos =
                new long[config.concurrentRetrievalIterations()];
        start.countDown();
        for (int index = 0; index < retrievalNanos.length; index++) {
            long started = System.nanoTime();
            query(layout);
            retrievalNanos[index] = System.nanoTime() - started;
        }

        CleanupMeasurement cleanupMeasurement =
                cleanup.get(5, TimeUnit.MINUTES);
        long walAfter = walPosition();
        long walBytes = walDiff(walAfter, walBefore);

        analyze(layout);
        flushStats();

        long deadRows = deadRows(layout);
        long bytes = relationBytes(layout);
        long leaves = leafCount(layout);

        return new Measurement(
                layout.name(),
                cleanupMeasurement.durationMs(),
                cleanupMeasurement.documentsPerSecond(),
                cleanupMeasurement.payloadRowsPerSecond(),
                walBytes,
                deadRows,
                bytes,
                leaves,
                cleanupMeasurement.logicalPayloadMutations(),
                percentileMs(retrievalNanos, 0.50),
                percentileMs(retrievalNanos, 0.95),
                percentileMs(retrievalNanos, 0.99)
        );
    }

    private static CleanupMeasurement cleanup(Layout layout)
            throws Exception {
        long started = System.nanoTime();
        long logicalMutations = 0;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            for (int document = 0;
                    document < config.purgeDocuments();
                    document++) {
                String documentId = documentId(document);

                if (layout == Layout.A) {
                    logicalMutations += execute(
                            connection,
                            """
                            UPDATE bench_a_vector
                            SET storage_state = 1
                            WHERE access_level = 1
                              AND document_id = ?
                              AND generation = 1
                              AND storage_state = 0
                            """,
                            documentId
                    );
                    logicalMutations += execute(
                            connection,
                            """
                            UPDATE bench_a_projection
                            SET storage_state = 1
                            WHERE access_level = 1
                              AND document_id = ?
                              AND generation = 1
                              AND storage_state = 0
                            """,
                            documentId
                    );
                    logicalMutations += execute(
                            connection,
                            """
                            DELETE FROM bench_a_vector
                            WHERE access_level = 1
                              AND document_id = ?
                              AND generation = 1
                              AND storage_state = 1
                            """,
                            documentId
                    );
                    logicalMutations += execute(
                            connection,
                            """
                            DELETE FROM bench_a_projection
                            WHERE access_level = 1
                              AND document_id = ?
                              AND generation = 1
                              AND storage_state = 1
                            """,
                            documentId
                    );
                } else {
                    long vectorRows = execute(
                            connection,
                            """
                            DELETE FROM bench_d_vector
                            WHERE access_level = 1
                              AND document_id = ?
                              AND generation = 1
                            """,
                            documentId
                    );
                    long projectionRows = execute(
                            connection,
                            """
                            DELETE FROM bench_d_projection
                            WHERE access_level = 1
                              AND document_id = ?
                              AND generation = 1
                            """,
                            documentId
                    );
                    logicalMutations += vectorRows + projectionRows;
                    try (PreparedStatement ps = connection.prepareStatement(
                            """
                            INSERT INTO bench_d_tombstone (
                                document_id,
                                generation,
                                vector_count,
                                projection_count,
                                retired_at
                            ) VALUES (?, 1, ?, ?, clock_timestamp())
                            """
                    )) {
                        ps.setString(1, documentId);
                        ps.setLong(2, vectorRows);
                        ps.setLong(3, projectionRows);
                        ps.executeUpdate();
                    }
                }

                connection.commit();
            }
        }

        long durationNanos = System.nanoTime() - started;
        double seconds = durationNanos / 1_000_000_000.0;
        long payloadRows =
                (long) config.purgeDocuments()
                        * config.chunksPerDocument()
                        * 2L;

        return new CleanupMeasurement(
                durationNanos / 1_000_000.0,
                config.purgeDocuments() / seconds,
                payloadRows / seconds,
                logicalMutations
        );
    }

    private static int execute(
            Connection connection,
            String sql,
            String documentId
    ) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, documentId);
            return ps.executeUpdate();
        }
    }

    private static void warmup(Layout layout) {
        for (int index = 0; index < config.warmups(); index++) {
            query(layout);
        }
    }

    private static void query(Layout layout) {
        String table = layout == Layout.A
                ? "bench_a_vector"
                : "bench_d_vector";
        String statePredicate = layout == Layout.A
                ? " AND storage_state = 0"
                : "";

        jdbc.queryForList(
                """
                SELECT id
                FROM %s
                WHERE access_level = 1
                  AND language = 'en'
                  %s
                ORDER BY embedding <=> ?
                LIMIT %d
                """.formatted(table, statePredicate, TOP_K),
                Long.class,
                new PGvector(queryVector)
        );
    }

    private static void createSchema() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");

        jdbc.execute("""
                CREATE TABLE bench_a_vector (
                    access_level BIGINT NOT NULL,
                    language VARCHAR(16) NOT NULL,
                    storage_state SMALLINT NOT NULL,
                    document_id VARCHAR(100) NOT NULL,
                    generation BIGINT NOT NULL,
                    id BIGINT NOT NULL,
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
                CREATE TABLE bench_a_projection (
                    access_level BIGINT NOT NULL,
                    language VARCHAR(16) NOT NULL,
                    storage_state SMALLINT NOT NULL,
                    document_id VARCHAR(100) NOT NULL,
                    generation BIGINT NOT NULL,
                    chunk_id BIGINT NOT NULL,
                    text_content TEXT NOT NULL,
                    search_vector TSVECTOR GENERATED ALWAYS AS (
                        to_tsvector('simple', text_content)
                    ) STORED,
                    PRIMARY KEY (
                        access_level,
                        language,
                        storage_state,
                        chunk_id
                    )
                )
                PARTITION BY LIST (access_level)
                """);

        jdbc.execute("""
                CREATE TABLE bench_d_vector (
                    access_level BIGINT NOT NULL,
                    language VARCHAR(16) NOT NULL,
                    document_id VARCHAR(100) NOT NULL,
                    generation BIGINT NOT NULL,
                    id BIGINT NOT NULL,
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
                CREATE TABLE bench_d_projection (
                    access_level BIGINT NOT NULL,
                    language VARCHAR(16) NOT NULL,
                    document_id VARCHAR(100) NOT NULL,
                    generation BIGINT NOT NULL,
                    chunk_id BIGINT NOT NULL,
                    text_content TEXT NOT NULL,
                    search_vector TSVECTOR GENERATED ALWAYS AS (
                        to_tsvector('simple', text_content)
                    ) STORED,
                    PRIMARY KEY (
                        access_level,
                        language,
                        chunk_id
                    )
                )
                PARTITION BY LIST (access_level)
                """);
        jdbc.execute("""
                CREATE TABLE bench_d_tombstone (
                    document_id VARCHAR(100) NOT NULL,
                    generation BIGINT NOT NULL,
                    vector_count BIGINT NOT NULL,
                    projection_count BIGINT NOT NULL,
                    retired_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (document_id, generation)
                )
                """);

        createAccessAndLanguagePartitions("bench_a_vector", true);
        createAccessAndLanguagePartitions("bench_a_projection", true);
        createAccessAndLanguagePartitions("bench_d_vector", false);
        createAccessAndLanguagePartitions("bench_d_projection", false);
    }

    private static void createAccessAndLanguagePartitions(
            String parent,
            boolean storageState
    ) {
        String access = parent + "_al_1";
        jdbc.execute("""
                CREATE TABLE %s
                PARTITION OF %s
                FOR VALUES IN (1)
                PARTITION BY LIST (language)
                """.formatted(access, parent));

        for (String language : LANGUAGES) {
            String languageLeaf = access + "_lang_" + language;
            if (storageState) {
                jdbc.execute("""
                        CREATE TABLE %s
                        PARTITION OF %s
                        FOR VALUES IN ('%s')
                        PARTITION BY LIST (storage_state)
                        """.formatted(languageLeaf, access, language));
                jdbc.execute("""
                        CREATE TABLE %s_s0
                        PARTITION OF %s
                        FOR VALUES IN (0)
                        """.formatted(languageLeaf, languageLeaf));
                jdbc.execute("""
                        CREATE TABLE %s_s1
                        PARTITION OF %s
                        FOR VALUES IN (1)
                        """.formatted(languageLeaf, languageLeaf));
            } else {
                jdbc.execute("""
                        CREATE TABLE %s
                        PARTITION OF %s
                        FOR VALUES IN ('%s')
                        """.formatted(languageLeaf, access, language));
            }
        }
    }

    private static void seed() {
        List<SeedRow> batch = new ArrayList<>(250);
        long id = 1L;

        for (int document = 0; document < config.documents(); document++) {
            for (int chunk = 0;
                    chunk < config.chunksPerDocument();
                    chunk++) {
                String language =
                        LANGUAGES.get(chunk % LANGUAGES.size());
                batch.add(new SeedRow(
                        id,
                        documentId(document),
                        language,
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

    private static void insertBatch(List<SeedRow> rows) {
        insertVectorBatch("bench_a_vector", true, rows);
        insertProjectionBatch("bench_a_projection", true, rows);
        insertVectorBatch("bench_d_vector", false, rows);
        insertProjectionBatch("bench_d_projection", false, rows);
    }

    private static void insertVectorBatch(
            String table,
            boolean storageState,
            List<SeedRow> rows
    ) {
        String columns = storageState
                ? "access_level, language, storage_state, document_id, generation, id, embedding"
                : "access_level, language, document_id, generation, id, embedding";
        String values = storageState
                ? "1, ?, 0, ?, 1, ?, ?"
                : "1, ?, ?, 1, ?, ?";

        jdbc.batchUpdate(
                "INSERT INTO " + table + " (" + columns + ") VALUES ("
                        + values + ")",
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setString(1, row.language());
                    ps.setString(2, row.documentId());
                    ps.setLong(3, row.id());
                    ps.setObject(4, new PGvector(row.embedding()));
                }
        );
    }

    private static void insertProjectionBatch(
            String table,
            boolean storageState,
            List<SeedRow> rows
    ) {
        String columns = storageState
                ? "access_level, language, storage_state, document_id, generation, chunk_id, text_content"
                : "access_level, language, document_id, generation, chunk_id, text_content";
        String values = storageState
                ? "1, ?, 0, ?, 1, ?, ?"
                : "1, ?, ?, 1, ?, ?";

        jdbc.batchUpdate(
                "INSERT INTO " + table + " (" + columns + ") VALUES ("
                        + values + ")",
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setString(1, row.language());
                    ps.setString(2, row.documentId());
                    ps.setLong(3, row.id());
                    ps.setString(4, "benchmark document " + row.documentId());
                }
        );
    }

    private static void createIndexes() {
        for (String language : LANGUAGES) {
            String aVector = "bench_a_vector_al_1_lang_" + language;
            String aProjection =
                    "bench_a_projection_al_1_lang_" + language;
            String dVector = "bench_d_vector_al_1_lang_" + language;
            String dProjection =
                    "bench_d_projection_al_1_lang_" + language;

            jdbc.execute("""
                    CREATE INDEX %s_s0_hnsw
                    ON %s_s0
                    USING HNSW (embedding vector_cosine_ops)
                    """.formatted(aVector, aVector));
            jdbc.execute("""
                    CREATE INDEX %s_s0_doc
                    ON %s_s0 (document_id, generation)
                    """.formatted(aVector, aVector));
            jdbc.execute("""
                    CREATE INDEX %s_s1_doc
                    ON %s_s1 (document_id, generation)
                    """.formatted(aVector, aVector));
            jdbc.execute("""
                    CREATE INDEX %s_s0_fts
                    ON %s_s0
                    USING GIN (search_vector)
                    """.formatted(aProjection, aProjection));
            jdbc.execute("""
                    CREATE INDEX %s_s0_doc
                    ON %s_s0 (document_id, generation)
                    """.formatted(aProjection, aProjection));
            jdbc.execute("""
                    CREATE INDEX %s_s1_doc
                    ON %s_s1 (document_id, generation)
                    """.formatted(aProjection, aProjection));

            jdbc.execute("""
                    CREATE INDEX %s_hnsw
                    ON %s
                    USING HNSW (embedding vector_cosine_ops)
                    """.formatted(dVector, dVector));
            jdbc.execute("""
                    CREATE INDEX %s_doc
                    ON %s (document_id, generation)
                    """.formatted(dVector, dVector));
            jdbc.execute("""
                    CREATE INDEX %s_fts
                    ON %s
                    USING GIN (search_vector)
                    """.formatted(dProjection, dProjection));
            jdbc.execute("""
                    CREATE INDEX %s_doc
                    ON %s (document_id, generation)
                    """.formatted(dProjection, dProjection));
        }
    }

    private static void analyzeAll() {
        analyze(Layout.A);
        analyze(Layout.D);
    }

    private static void analyze(Layout layout) {
        if (layout == Layout.A) {
            jdbc.execute("ANALYZE bench_a_vector");
            jdbc.execute("ANALYZE bench_a_projection");
        } else {
            jdbc.execute("ANALYZE bench_d_vector");
            jdbc.execute("ANALYZE bench_d_projection");
            jdbc.execute("ANALYZE bench_d_tombstone");
        }
    }

    private static void checkpointAndFlushStats() {
        jdbc.execute("CHECKPOINT");
        flushStats();
    }

    private static void flushStats() {
        jdbc.execute("SELECT pg_stat_force_next_flush()");
    }

    private static long walPosition() {
        String value = jdbc.queryForObject(
                "SELECT pg_current_wal_insert_lsn()::text",
                String.class
        );
        if (value == null) {
            throw new IllegalStateException("Cannot read WAL position");
        }
        return lsnToLong(value);
    }

    private static long walDiff(long after, long before) {
        return Math.max(0L, after - before);
    }

    private static long lsnToLong(String lsn) {
        String[] parts = lsn.split("/");
        return (Long.parseUnsignedLong(parts[0], 16) << 32)
                + Long.parseUnsignedLong(parts[1], 16);
    }

    private static long deadRows(Layout layout) {
        String prefix = layout == Layout.A
                ? "bench_a_%"
                : "bench_d_%";
        Long value = jdbc.queryForObject(
                """
                SELECT COALESCE(sum(n_dead_tup), 0)::bigint
                FROM pg_stat_user_tables
                WHERE relname LIKE ?
                """,
                Long.class,
                prefix
        );
        return value == null ? 0L : value;
    }

    private static long relationBytes(Layout layout) {
        String vector = layout == Layout.A
                ? "bench_a_vector"
                : "bench_d_vector";
        String projection = layout == Layout.A
                ? "bench_a_projection"
                : "bench_d_projection";
        long bytes = tableTreeBytes(vector) + tableTreeBytes(projection);
        if (layout == Layout.D) {
            Long tombstone = jdbc.queryForObject(
                    """
                    SELECT pg_total_relation_size(
                        'bench_d_tombstone'::regclass
                    )::bigint
                    """,
                    Long.class
            );
            bytes += tombstone == null ? 0L : tombstone;
        }
        return bytes;
    }

    private static long tableTreeBytes(String table) {
        Long value = jdbc.queryForObject(
                """
                SELECT COALESCE(
                           sum(pg_total_relation_size(relid)),
                           0
                       )::bigint
                FROM pg_partition_tree(?::regclass)
                WHERE isleaf
                """,
                Long.class,
                table
        );
        return value == null ? 0L : value;
    }

    private static long leafCount(Layout layout) {
        String vector = layout == Layout.A
                ? "bench_a_vector"
                : "bench_d_vector";
        String projection = layout == Layout.A
                ? "bench_a_projection"
                : "bench_d_projection";
        return partitionLeaves(vector) + partitionLeaves(projection);
    }

    private static long partitionLeaves(String table) {
        Long value = jdbc.queryForObject(
                """
                SELECT count(*)::bigint
                FROM pg_partition_tree(?::regclass)
                WHERE isleaf
                """,
                Long.class,
                table
        );
        return value == null ? 0L : value;
    }

    private static String documentId(int document) {
        return "doc-" + String.format("%04d", document);
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

    private enum Layout {
        A,
        D
    }

    private record SeedRow(
            long id,
            String documentId,
            String language,
            float[] embedding
    ) {
    }

    private record CleanupMeasurement(
            double durationMs,
            double documentsPerSecond,
            double payloadRowsPerSecond,
            long logicalPayloadMutations
    ) {
    }

    private record Measurement(
            String layout,
            double cleanupDurationMs,
            double cleanupDocumentsPerSecond,
            double cleanupPayloadRowsPerSecond,
            long walBytes,
            long estimatedDeadRows,
            long physicalBytes,
            long leafCount,
            long logicalPayloadMutations,
            double retrievalP50Ms,
            double retrievalP95Ms,
            double retrievalP99Ms
    ) {
    }

    private record Config(
            int dimensions,
            int documents,
            int purgeDocuments,
            int chunksPerDocument,
            int warmups,
            int concurrentRetrievalIterations
    ) {
        static Config fromEnvironment() {
            int documents = envInt(
                    "AKMAI_PURGE_BENCH_DOCUMENTS",
                    36
            );
            int purgeDocuments = envInt(
                    "AKMAI_PURGE_BENCH_PURGE_DOCUMENTS",
                    18
            );
            if (purgeDocuments <= 0 || purgeDocuments >= documents) {
                throw new IllegalArgumentException(
                        "purge documents must be > 0 and < documents"
                );
            }
            return new Config(
                    envInt("AKMAI_PURGE_BENCH_DIMENSIONS", 1024),
                    documents,
                    purgeDocuments,
                    envInt("AKMAI_PURGE_BENCH_CHUNKS_PER_DOCUMENT", 100),
                    envInt("AKMAI_PURGE_BENCH_WARMUPS", 3),
                    envInt("AKMAI_PURGE_BENCH_RETRIEVAL_ITERATIONS", 80)
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
