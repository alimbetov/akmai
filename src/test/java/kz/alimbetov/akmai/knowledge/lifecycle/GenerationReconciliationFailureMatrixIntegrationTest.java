package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import kz.alimbetov.akmai.config.ReconciliationProperties;
import kz.alimbetov.akmai.knowledge.audit.AuditEventRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class GenerationReconciliationFailureMatrixIntegrationTest {

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
    static EmbeddingProfileStorageManager storage;
    static EmbeddingProfileRepository profiles;
    static PostgresGenerationVectorRepository vectors;
    static EmbeddingProfile profile;

    @BeforeAll
    static void setup() throws Exception {
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
        storage = new EmbeddingProfileStorageManager(jdbc);
        profiles = new EmbeddingProfileRepository(jdbc);
        profile = new EmbeddingProfile(
                "ep-reconciliation-failure-matrix",
                "test",
                "deterministic",
                3,
                "COSINE_DISTANCE",
                "test",
                "reconciliation-failure-matrix",
                "akmai_vector",
                "p_reconciliation_failure_matrix",
                "NONE",
                (short) 1,
                Instant.parse("2026-10-09T00:00:00Z")
        );
        storage.ensureStorage(profile);
        profiles.save(profile);
        vectors = new PostgresGenerationVectorRepository(
                jdbc,
                new ObjectMapper(),
                storage
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_audit_event");
        jdbc.update("DELETE FROM knowledge_retired_generation");
        jdbc.update("DELETE FROM akmai_vector.p_reconciliation_failure_matrix");
        jdbc.update("DELETE FROM knowledge_chunk_association");
        jdbc.update("DELETE FROM knowledge_reference_edge");
        jdbc.update("DELETE FROM knowledge_reference_target");
        jdbc.update("DELETE FROM document_identifier");
        jdbc.update("DELETE FROM knowledge_search_projection");
        jdbc.update("DELETE FROM knowledge_document_vector_generation");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void corruptRetiredTombstoneDoesNotStarveHealthyCandidate() {
        insertLifecycle("doc-corrupt", 2L);
        insertGeneration("doc-corrupt", 1L, "RETIRED", profile.profileId(), 60);
        insertLifecycle("doc-healthy", 2L);
        insertGeneration("doc-healthy", 1L, "FAILED", profile.profileId(), 30);

        int cleaned = service(10, 1, 5).reconcileBatch();

        assertThat(cleaned).isEqualTo(1);
        assertThat(status("doc-corrupt", 1L)).isEqualTo("RETIRED");
        assertThat(lastError("doc-corrupt", 1L))
                .contains("Retired generation tombstone is missing");
        assertThat(status("doc-healthy", 1L)).isEqualTo("CLEANED");
        assertThat(auditCount(
                "GENERATION_RECONCILIATION_FAILED",
                "doc-corrupt",
                1L
        )).isEqualTo(1);
        assertThat(auditCount(
                "GENERATION_VERIFIED",
                "doc-healthy",
                1L
        )).isEqualTo(1);
    }

    @Test
    void boundedResidualIsDeferredAndNextRunCanFinishCleanup() {
        insertLifecycle("doc-residual", 2L);
        insertGeneration("doc-residual", 1L, "RETIRED", profile.profileId(), 30);
        insertPurgedTombstone("doc-residual", 1L);
        insertProjection("doc-residual", 1L, "chunk-1", 0);
        insertProjection("doc-residual", 1L, "chunk-2", 1);

        GenerationReconciliationService reconciliation = service(1, 1, 5);

        assertThat(reconciliation.reconcileBatch()).isZero();
        assertThat(status("doc-residual", 1L)).isEqualTo("RETIRED");
        assertThat(lastError("doc-residual", 1L))
                .contains("Residual retrieval rows remain");
        assertThat(projectionCount("doc-residual", 1L)).isEqualTo(1);
        assertThat(auditCount(
                "GENERATION_REPAIR_DEFERRED",
                "doc-residual",
                1L
        )).isEqualTo(1);

        assertThat(reconciliation.reconcileBatch()).isEqualTo(1);
        assertThat(status("doc-residual", 1L)).isEqualTo("CLEANED");
        assertThat(projectionCount("doc-residual", 1L)).isZero();
        assertThat(auditCount(
                "GENERATION_REPAIRED",
                "doc-residual",
                1L
        )).isEqualTo(1);
    }

    @Test
    void currentlyPublishedGenerationIsNeverSelectedForRepair() {
        insertLifecycle("doc-published", 1L);
        insertGeneration("doc-published", 1L, "FAILED", profile.profileId(), 30);
        insertProjection("doc-published", 1L, "published-chunk", 0);

        assertThat(service(10, 1, 5).reconcileBatch()).isZero();
        assertThat(status("doc-published", 1L)).isEqualTo("FAILED");
        assertThat(projectionCount("doc-published", 1L)).isEqualTo(1);
        assertThat(auditCount(
                "GENERATION_REPAIRED",
                "doc-published",
                1L
        )).isZero();
    }

    @Test
    void databaseFailureIsFailFastAndLeavesGenerationRetryable() {
        EmbeddingProfile broken = new EmbeddingProfile(
                "ep-reconciliation-broken",
                "test",
                "deterministic",
                3,
                "COSINE_DISTANCE",
                "test",
                "broken",
                "akmai_vector",
                "p_reconciliation_missing_table",
                "NONE",
                (short) 1,
                Instant.parse("2026-10-09T00:00:00Z")
        );
        profiles.save(broken);
        insertLifecycle("doc-db-failure", 2L);
        insertGeneration(
                "doc-db-failure",
                1L,
                "FAILED",
                broken.profileId(),
                30
        );

        assertThatThrownBy(() -> service(10, 1, 5).reconcileBatch())
                .isInstanceOf(DataAccessException.class);

        assertThat(status("doc-db-failure", 1L)).isEqualTo("FAILED");
        assertThat(cleanupRequired("doc-db-failure", 1L)).isTrue();
        assertThat(auditCount(
                "GENERATION_RECONCILIATION_FAILED",
                "doc-db-failure",
                1L
        )).isZero();
    }

    @Test
    void repairTimeoutRollsBackPassAndLeavesPayloadRetryable() throws Exception {
        insertLifecycle("doc-timeout", 2L);
        insertGeneration("doc-timeout", 1L, "FAILED", profile.profileId(), 30);
        insertProjection("doc-timeout", 1L, "locked-chunk", 0);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT chunk_id
                    FROM knowledge_search_projection
                    WHERE access_level = 1
                      AND document_id = 'doc-timeout'
                      AND generation = 1
                    FOR UPDATE
                    """
            )) {
                statement.executeQuery();
            }

            assertThatThrownBy(() -> service(10, 1, 1).reconcileBatch())
                    .isInstanceOf(DataAccessException.class);

            assertThat(status("doc-timeout", 1L)).isEqualTo("FAILED");
            assertThat(cleanupRequired("doc-timeout", 1L)).isTrue();
            assertThat(projectionCount("doc-timeout", 1L)).isEqualTo(1);
            connection.rollback();
        }
    }

    private GenerationReconciliationService service(
            int batchSize,
            int maxBatches,
            int repairTimeoutSeconds
    ) {
        ReconciliationProperties properties = new ReconciliationProperties(
                true,
                batchSize,
                maxBatches,
                Duration.ZERO,
                Duration.ofMinutes(5)
        );
        DataSourceTransactionManager manager =
                new DataSourceTransactionManager(dataSource);
        TransactionTemplate cleanupTx = new TransactionTemplate(manager);
        TransactionTemplate repairTx = new TransactionTemplate(manager);
        repairTx.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        repairTx.setTimeout(repairTimeoutSeconds);
        GenerationRepairService repair = new GenerationRepairService(
                jdbc,
                storage,
                properties,
                repairTx
        );
        return new GenerationReconciliationService(
                jdbc,
                cleanupTx,
                vectors,
                profiles,
                repair,
                properties,
                new AuditEventRepository(jdbc, new ObjectMapper())
        );
    }

    private void insertLifecycle(String documentId, long publishedGeneration) {
        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id, lifecycle_policy, lifecycle_status,
                    generation, attempt_count, row_version,
                    retention_status, published_generation,
                    next_generation, access_level, created_at, updated_at
                ) VALUES (
                    ?, 'PERMANENT', 'READY', ?, 0, 0,
                    'ACTIVE', ?, ?, 1,
                    clock_timestamp(), clock_timestamp()
                )
                """,
                documentId,
                publishedGeneration,
                publishedGeneration,
                publishedGeneration + 1
        );
    }

    private void insertGeneration(
            String documentId,
            long generation,
            String generationStatus,
            String profileId,
            int ageMinutes
    ) {
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id, generation, generation_status,
                    generation_kind, embedding_profile_id,
                    content_fingerprint, physical_id_version,
                    access_level, cleanup_required, started_at,
                    failed_at, retired_at
                ) VALUES (
                    ?, ?, ?, 'INGESTION', ?, ?, 2, 1, true,
                    clock_timestamp() - (? * interval '1 minute'),
                    CASE WHEN ? = 'FAILED'
                         THEN clock_timestamp() - (? * interval '1 minute')
                         ELSE NULL END,
                    CASE WHEN ? IN ('RETIRING', 'RETIRED')
                         THEN clock_timestamp() - (? * interval '1 minute')
                         ELSE NULL END
                )
                """,
                documentId,
                generation,
                generationStatus,
                profileId,
                "fp-" + documentId + "-" + generation,
                ageMinutes,
                generationStatus,
                ageMinutes,
                generationStatus,
                ageMinutes
        );
    }

    private void insertPurgedTombstone(String documentId, long generation) {
        jdbc.update(
                """
                INSERT INTO knowledge_retired_generation (
                    document_id, generation, access_level,
                    embedding_profile_id, projection_count, vector_count,
                    content_fingerprint, physical_id_version,
                    retention_policy, purge_started_at, retired_at,
                    purge_after, cleanup_status, cleanup_attempts
                ) VALUES (
                    ?, ?, 1, ?, 2, 0,
                    ?, 2, 'PERMANENT',
                    clock_timestamp() - interval '31 minutes',
                    clock_timestamp() - interval '30 minutes',
                    clock_timestamp() + interval '7 days',
                    'PURGED', 1
                )
                """,
                documentId,
                generation,
                profile.profileId(),
                "fp-" + documentId + "-" + generation
        );
    }

    private void insertProjection(
            String documentId,
            long generation,
            String chunkId,
            int chunkIndex
    ) {
        jdbc.update(
                """
                INSERT INTO knowledge_search_projection (
                    access_level, document_id, generation,
                    chunk_id, chunk_index, text_content,
                    embedding_text, language, domain
                ) VALUES (
                    1, ?, ?, ?, ?, 'repair text',
                    'repair text', 'en', 'GENERAL'
                )
                """,
                documentId,
                generation,
                chunkId,
                chunkIndex
        );
    }

    private String status(String documentId, long generation) {
        return jdbc.queryForObject(
                """
                SELECT generation_status
                FROM knowledge_document_generation
                WHERE document_id = ? AND generation = ?
                """,
                String.class,
                documentId,
                generation
        );
    }

    private boolean cleanupRequired(String documentId, long generation) {
        Boolean value = jdbc.queryForObject(
                """
                SELECT cleanup_required
                FROM knowledge_document_generation
                WHERE document_id = ? AND generation = ?
                """,
                Boolean.class,
                documentId,
                generation
        );
        return Boolean.TRUE.equals(value);
    }

    private String lastError(String documentId, long generation) {
        return jdbc.queryForObject(
                """
                SELECT last_error
                FROM knowledge_document_generation
                WHERE document_id = ? AND generation = ?
                """,
                String.class,
                documentId,
                generation
        );
    }

    private int projectionCount(String documentId, long generation) {
        Integer value = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_search_projection
                WHERE access_level = 1
                  AND document_id = ?
                  AND generation = ?
                """,
                Integer.class,
                documentId,
                generation
        );
        return value == null ? 0 : value;
    }

    private int auditCount(String eventType, String documentId, long generation) {
        Integer value = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_audit_event
                WHERE event_type = ?
                  AND document_id = ?
                  AND generation = ?
                """,
                Integer.class,
                eventType,
                documentId,
                generation
        );
        return value == null ? 0 : value;
    }
}
