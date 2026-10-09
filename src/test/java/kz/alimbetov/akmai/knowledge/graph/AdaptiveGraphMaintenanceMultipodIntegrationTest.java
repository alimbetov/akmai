package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class AdaptiveGraphMaintenanceMultipodIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres")
            )
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    static PGSimpleDataSource dataSource;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;

    @BeforeAll
    static void migrate() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(
                "classpath:db/changelog/db.changelog-master.yaml"
        );
        liquibase.afterPropertiesSet();

        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_chunk_association");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void lockedCandidateIsSkippedWhileAnotherPairMakesProgress()
            throws Exception {
        AdaptiveGraphProperties properties =
                AdaptiveGraphTestProperties.create(
                        new AdaptiveGraphProperties.BandQuotas(8, 8, 16)
                );
        AdaptiveChunkGraphRepository repository =
                new AdaptiveChunkGraphRepository(jdbc, tx);
        AdaptiveGraphMaintenanceService maintenance = maintenance(properties);

        insertGeneration("doc-a");
        insertGeneration("doc-b");
        insertGeneration("doc-c");
        insertGeneration("doc-d");
        reinforce(repository, node("doc-a", "a"), node("doc-b", "b"));
        reinforce(repository, node("doc-c", "c"), node("doc-d", "d"));

        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement statement = blocker.prepareStatement(
                    """
                    SELECT 1
                    FROM knowledge_chunk_association
                    WHERE source_document_id = 'doc-a'
                      AND target_document_id = 'doc-b'
                    FOR UPDATE
                    """
            )) {
                assertThat(statement.executeQuery().next()).isTrue();
            }

            AdaptiveGraphMaintenanceService.MaintenanceBatch first =
                    maintenance.maintainBatch();

            assertThat(first.scored()).isEqualTo(1);
            assertThat(lastScored("doc-a", "doc-b")).isFalse();
            assertThat(lastScored("doc-c", "doc-d")).isTrue();

            blocker.rollback();
        }

        AdaptiveGraphMaintenanceService.MaintenanceBatch second =
                maintenance.maintainBatch();

        assertThat(second.scored()).isEqualTo(1);
        assertThat(lastScored("doc-a", "doc-b")).isTrue();
    }

    @Test
    void overlappingCompactionWorkersDoNotDeadlockOrLeaveHalfPairs()
            throws Exception {
        AdaptiveGraphProperties properties =
                AdaptiveGraphTestProperties.create(
                        new AdaptiveGraphProperties.BandQuotas(1, 1, 1)
                );
        AdaptiveChunkGraphRepository repository =
                new AdaptiveChunkGraphRepository(jdbc, tx);

        insertGeneration("doc-a");
        insertGeneration("doc-b");
        insertGeneration("doc-c");
        insertGeneration("doc-d");

        reinforce(repository, node("doc-a", "a"), node("doc-b", "b"));
        reinforce(repository, node("doc-a", "a"), node("doc-c", "c"));
        reinforce(repository, node("doc-b", "b"), node("doc-d", "d"));

        jdbc.update(
                """
                UPDATE knowledge_chunk_association
                SET last_scored_at = clock_timestamp(),
                    compaction_required = TRUE
                """
        );

        AdaptiveGraphMaintenanceService podA = maintenance(properties);
        AdaptiveGraphMaintenanceService podB = maintenance(properties);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<?> first = executor.submit(() -> {
                await(start);
                for (int run = 0; run < 4; run++) {
                    podA.maintainBatch();
                }
            });
            Future<?> second = executor.submit(() -> {
                await(start);
                for (int run = 0; run < 4; run++) {
                    podB.maintainBatch();
                }
            });

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertSymmetricPair("doc-a", "doc-b");
        assertSymmetricPair("doc-a", "doc-c");
        assertSymmetricPair("doc-b", "doc-d");
        assertThat(candidateDegree("doc-a", "a")).isLessThanOrEqualTo(1);
        assertThat(candidateDegree("doc-b", "b")).isLessThanOrEqualTo(1);
    }

    private AdaptiveGraphMaintenanceService maintenance(
            AdaptiveGraphProperties properties
    ) {
        return new AdaptiveGraphMaintenanceService(
                jdbc,
                tx,
                properties,
                new AdaptiveGraphScoreCalculator(properties),
                new GraphMutationLocks(jdbc)
        );
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertSymmetricPair(String left, String right) {
        Integer rows = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_chunk_association
                WHERE (source_document_id = ? AND target_document_id = ?)
                   OR (source_document_id = ? AND target_document_id = ?)
                """,
                Integer.class,
                left,
                right,
                right,
                left
        );
        assertThat(rows).isIn(0, 2);
    }

    private int candidateDegree(String documentId, String chunkId) {
        Integer rows = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_chunk_association
                WHERE source_document_id = ?
                  AND source_chunk_id = ?
                  AND band = 'CANDIDATE'
                """,
                Integer.class,
                documentId,
                chunkId
        );
        return rows == null ? 0 : rows;
    }

    private boolean lastScored(String source, String target) {
        Boolean scored = jdbc.queryForObject(
                """
                SELECT last_scored_at IS NOT NULL
                FROM knowledge_chunk_association
                WHERE source_document_id = ?
                  AND target_document_id = ?
                """,
                Boolean.class,
                source,
                target
        );
        return Boolean.TRUE.equals(scored);
    }

    private void reinforce(
            AdaptiveChunkGraphRepository repository,
            ChunkGraphNode left,
            ChunkGraphNode right
    ) {
        repository.reinforceSymmetric(
                left,
                right,
                AssociationBand.CANDIDATE,
                new AssociationEvidence(
                        0.0,
                        1,
                        1,
                        0,
                        1,
                        Instant.now(),
                        1
                )
        );
    }

    private ChunkGraphNode node(String documentId, String chunkId) {
        return new ChunkGraphNode(1, documentId, 1, chunkId);
    }

    private void insertGeneration(String documentId) {
        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id,
                    lifecycle_policy,
                    lifecycle_status,
                    generation,
                    attempt_count,
                    row_version,
                    created_at,
                    updated_at,
                    retention_status,
                    published_generation,
                    next_generation,
                    access_level
                ) VALUES (
                    ?, 'PERMANENT', 'READY',
                    1, 0, 0,
                    clock_timestamp(), clock_timestamp(), 'ACTIVE',
                    1, 2, 1
                )
                """,
                documentId
        );
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id,
                    generation,
                    generation_status,
                    generation_kind,
                    access_level
                ) VALUES (?, 1, 'PUBLISHED', 'INGESTION', 1)
                """,
                documentId
        );
    }
}
