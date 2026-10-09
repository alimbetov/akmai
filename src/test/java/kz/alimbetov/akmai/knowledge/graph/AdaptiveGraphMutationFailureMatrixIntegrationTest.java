package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class AdaptiveGraphMutationFailureMatrixIntegrationTest {

    private static final String TEST_CONSTRAINT =
            "graph_test_reject_doc_b_source";

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
    static AdaptiveChunkGraphRepository online;
    static SemanticAssociationSeedRepository semantic;
    static AdaptiveChunkGraphRepository shortOnline;
    static SemanticAssociationSeedRepository shortSemantic;

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
        DataSourceTransactionManager manager =
                new DataSourceTransactionManager(dataSource);

        TransactionTemplate normal = new TransactionTemplate(manager);
        normal.setTimeout(5);
        TransactionTemplate shortTimeout = new TransactionTemplate(manager);
        shortTimeout.setTimeout(1);

        online = new AdaptiveChunkGraphRepository(jdbc, normal);
        semantic = new SemanticAssociationSeedRepository(jdbc, normal);
        shortOnline = new AdaptiveChunkGraphRepository(jdbc, shortTimeout);
        shortSemantic = new SemanticAssociationSeedRepository(
                jdbc,
                shortTimeout
        );
    }

    @BeforeEach
    void clean() {
        dropTestConstraint();
        jdbc.update("DELETE FROM knowledge_chunk_association");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void sharedMutationLocksRequireAnActiveDatabaseTransaction() {
        insertPublished("doc-a");
        insertPublished("doc-b");

        GraphMutationLocks locks = new GraphMutationLocks(jdbc);

        assertThatThrownBy(() -> locks.lockEligiblePublishedNodes(
                List.of(node("doc-a"), node("doc-b"))
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active database transaction");
    }

    @Test
    void lifecyclePointerCannotAuthorizeNonPublishedGeneration() {
        insertPublished("doc-a");
        insertPublished("doc-b");
        jdbc.update(
                """
                UPDATE knowledge_document_generation
                SET generation_status = 'RETIRED',
                    retired_at = clock_timestamp(),
                    cleanup_required = true
                WHERE document_id = 'doc-b'
                  AND generation = 1
                """
        );

        assertThatThrownBy(() -> online.reinforceSymmetric(
                node("doc-a"),
                node("doc-b"),
                AssociationBand.CANDIDATE,
                evidence(17)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLISHED");

        assertThatThrownBy(() -> semantic.seedSymmetric(
                node("doc-a"),
                node("doc-b"),
                0.93,
                1,
                8,
                Instant.now()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLISHED");

        assertThat(associationCount()).isZero();
    }

    @Test
    void reversedOnlinePairConcurrencyDoesNotDeadlockOrCreateHalfPair()
            throws Exception {
        insertPublished("doc-a");
        insertPublished("doc-b");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier start = new CyclicBarrier(2);
            Future<?> forward = executor.submit(() -> {
                await(start);
                online.reinforceSymmetric(
                        node("doc-a"),
                        node("doc-b"),
                        AssociationBand.HOT,
                        evidence(21)
                );
            });
            Future<?> reverse = executor.submit(() -> {
                await(start);
                online.reinforceSymmetric(
                        node("doc-b"),
                        node("doc-a"),
                        AssociationBand.HOT,
                        evidence(22)
                );
            });

            forward.get(10, TimeUnit.SECONDS);
            reverse.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(associationCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT min(support_count) FROM knowledge_chunk_association",
                Long.class
        )).isEqualTo(2L);
    }

    @Test
    void reversedSemanticPairConcurrencySerializesDegreeAdmission()
            throws Exception {
        insertPublished("doc-a");
        insertPublished("doc-b");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier start = new CyclicBarrier(2);
            Future<Boolean> forward = executor.submit(() -> {
                await(start);
                return semantic.seedSymmetric(
                        node("doc-a"),
                        node("doc-b"),
                        0.91,
                        1,
                        1,
                        Instant.now()
                );
            });
            Future<Boolean> reverse = executor.submit(() -> {
                await(start);
                return semantic.seedSymmetric(
                        node("doc-b"),
                        node("doc-a"),
                        0.94,
                        1,
                        1,
                        Instant.now()
                );
            });

            assertThat(forward.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(reverse.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(associationCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT min(semantic_similarity) FROM knowledge_chunk_association",
                Double.class
        )).isEqualTo(0.94);
    }

    @Test
    void forcedSecondDirectionFailureRollsBackWholeBilateralWrite() {
        insertPublished("doc-a");
        insertPublished("doc-b");
        installSecondDirectionRejectConstraint();

        try {
            assertThatThrownBy(() -> online.reinforceSymmetric(
                    node("doc-a"),
                    node("doc-b"),
                    AssociationBand.CANDIDATE,
                    evidence(31)
            )).isInstanceOf(RuntimeException.class);
            assertThat(associationCount()).isZero();

            assertThatThrownBy(() -> semantic.seedSymmetric(
                    node("doc-a"),
                    node("doc-b"),
                    0.92,
                    1,
                    8,
                    Instant.now()
            )).isInstanceOf(RuntimeException.class);
            assertThat(associationCount()).isZero();
        } finally {
            dropTestConstraint();
        }
    }

    @Test
    void batchFailureRollsBackEarlierSuccessfulPair() {
        insertPublished("doc-a");
        insertPublished("doc-b");
        insertPublished("doc-c");
        installSecondDirectionRejectConstraint();

        try {
            assertThatThrownBy(() -> online.reinforceSymmetricBatch(List.of(
                    new AssociationObservation(
                            node("doc-a"),
                            node("doc-c"),
                            AssociationBand.CANDIDATE,
                            evidence(41)
                    ),
                    new AssociationObservation(
                            node("doc-a"),
                            node("doc-b"),
                            AssociationBand.CANDIDATE,
                            evidence(42)
                    )
            ))).isInstanceOf(RuntimeException.class);

            assertThat(associationCount()).isZero();
        } finally {
            dropTestConstraint();
        }
    }

    @Test
    void transactionTimeoutBoundsLifecycleLockWaitForOnlineAndSemantic()
            throws Exception {
        insertPublished("doc-a");
        insertPublished("doc-b");

        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement statement = blocker.prepareStatement(
                    """
                    SELECT 1
                    FROM knowledge_document_lifecycle
                    WHERE document_id = 'doc-a'
                    FOR UPDATE
                    """
            )) {
                statement.executeQuery().close();
            }

            assertTimesOut(() -> shortOnline.reinforceSymmetric(
                    node("doc-a"),
                    node("doc-b"),
                    AssociationBand.CANDIDATE,
                    evidence(51)
            ));
            assertThat(associationCount()).isZero();

            assertTimesOut(() -> shortSemantic.seedSymmetric(
                    node("doc-a"),
                    node("doc-b"),
                    0.95,
                    1,
                    8,
                    Instant.now()
            ));
            assertThat(associationCount()).isZero();

            blocker.rollback();
        }
    }

    private void assertTimesOut(Runnable operation) {
        long started = System.nanoTime();
        assertThatThrownBy(operation::run)
                .isInstanceOf(RuntimeException.class);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started
        );
        assertThat(elapsedMillis).isLessThan(4_000L);
    }

    private void installSecondDirectionRejectConstraint() {
        jdbc.execute(
                "ALTER TABLE knowledge_chunk_association "
                        + "ADD CONSTRAINT "
                        + TEST_CONSTRAINT
                        + " CHECK (source_document_id <> 'doc-b')"
        );
    }

    private void dropTestConstraint() {
        jdbc.execute(
                "ALTER TABLE knowledge_chunk_association "
                        + "DROP CONSTRAINT IF EXISTS "
                        + TEST_CONSTRAINT
        );
    }

    private void insertPublished(String documentId) {
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

    private ChunkGraphNode node(String documentId) {
        return new ChunkGraphNode(
                1,
                documentId,
                1,
                "chunk-" + documentId.substring(documentId.length() - 1)
        );
    }

    private AssociationEvidence evidence(int queryBucket) {
        return new AssociationEvidence(
                0.9,
                1,
                1,
                1,
                queryBucket,
                Instant.now(),
                1
        );
    }

    private int associationCount() {
        Integer value = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_chunk_association",
                Integer.class
        );
        return value == null ? 0 : value;
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("concurrency barrier failed", exception);
        }
    }
}
