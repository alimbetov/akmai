package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class GenerationReconciliationMultipodIntegrationTest {

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
    static EmbeddingProfile profile;
    static GenerationReconciliationService reconciliation;

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
        TransactionTemplate cleanupTx = new TransactionTemplate(manager);
        TransactionTemplate repairTx = new TransactionTemplate(manager);
        repairTx.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );

        EmbeddingProfileStorageManager storage =
                new EmbeddingProfileStorageManager(jdbc);
        profile = new EmbeddingProfile(
                "ep-reconciliation-multipod",
                "test",
                "deterministic",
                3,
                "COSINE_DISTANCE",
                "test",
                "reconciliation-multipod",
                "akmai_vector",
                "p_reconciliation_multipod",
                "NONE",
                (short) 1,
                Instant.parse("2026-10-08T00:00:00Z")
        );
        storage.ensureStorage(profile);

        EmbeddingProfileRepository profiles =
                new EmbeddingProfileRepository(jdbc);
        profiles.save(profile);

        ReconciliationProperties properties =
                new ReconciliationProperties(
                        true,
                        1,
                        1,
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(5)
                );
        GenerationRepairService repair = new GenerationRepairService(
                jdbc,
                storage,
                properties,
                repairTx
        );
        reconciliation = new GenerationReconciliationService(
                jdbc,
                cleanupTx,
                new PostgresGenerationVectorRepository(
                        jdbc,
                        new ObjectMapper(),
                        storage
                ),
                profiles,
                repair,
                properties,
                new AuditEventRepository(jdbc, new ObjectMapper())
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_audit_event");
        jdbc.update("DELETE FROM knowledge_retired_generation");
        jdbc.update("DELETE FROM akmai_vector.p_reconciliation_multipod");
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
    void busyCandidateIsSkippedWithoutBlockingAndRetriesAfterLockRelease()
            throws Exception {
        insertLifecycle("doc-busy", 2L);
        insertGeneration("doc-busy", 1L, "FAILED");

        long lockKey = candidateLockKey("doc-busy", 1L);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(?)"
            )) {
                statement.setLong(1, lockKey);
                statement.execute();
            }

            assertThat(reconciliation.reconcileBatch()).isZero();
            assertThat(status("doc-busy", 1L)).isEqualTo("FAILED");

            connection.rollback();
        }

        assertThat(reconciliation.reconcileBatch()).isEqualTo(1);
        assertThat(status("doc-busy", 1L)).isEqualTo("CLEANED");
    }

    @Test
    void busyFirstPageDoesNotStarveUnlockedTailCandidate() throws Exception {
        insertLifecycle("doc-a-busy", 2L);
        insertGeneration("doc-a-busy", 1L, "FAILED");
        insertLifecycle("doc-b-tail", 2L);
        insertGeneration("doc-b-tail", 1L, "FAILED");

        jdbc.update(
                """
                UPDATE knowledge_document_generation
                SET failed_at = clock_timestamp() - interval '2 hours'
                WHERE document_id = 'doc-a-busy'
                  AND generation = 1
                """
        );

        long lockKey = candidateLockKey("doc-a-busy", 1L);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(?)"
            )) {
                statement.setLong(1, lockKey);
                statement.execute();
            }

            assertThat(reconciliation.reconcileBatch()).isEqualTo(1);
            assertThat(status("doc-a-busy", 1L)).isEqualTo("FAILED");
            assertThat(status("doc-b-tail", 1L)).isEqualTo("CLEANED");

            connection.rollback();
        }
    }

    @Test
    void freshPurgingTombstoneIsNotStolenButStaleClaimIsRecovered() {
        insertLifecycle("doc-purge", 2L);
        insertGeneration("doc-purge", 1L, "RETIRING");
        jdbc.update(
                """
                INSERT INTO knowledge_retired_generation (
                    document_id,
                    generation,
                    access_level,
                    embedding_profile_id,
                    projection_count,
                    vector_count,
                    content_fingerprint,
                    physical_id_version,
                    retention_policy,
                    purge_started_at,
                    cleanup_status,
                    cleanup_attempts
                ) VALUES (
                    'doc-purge', 1, 1, ?, 0, 0,
                    'fp-doc-purge-1', 2, 'PERMANENT',
                    clock_timestamp(), 'PURGING', 1
                )
                """,
                profile.profileId()
        );

        assertThat(reconciliation.reconcileBatch()).isZero();
        assertThat(status("doc-purge", 1L)).isEqualTo("RETIRING");
        assertThat(tombstoneStatus("doc-purge", 1L)).isEqualTo("PURGING");

        jdbc.update(
                """
                UPDATE knowledge_retired_generation
                SET purge_started_at = clock_timestamp() - interval '10 minutes'
                WHERE document_id = 'doc-purge'
                  AND generation = 1
                  AND access_level = 1
                """
        );

        assertThat(reconciliation.reconcileBatch()).isZero();
        assertThat(status("doc-purge", 1L)).isEqualTo("RETIRED");
        assertThat(tombstoneStatus("doc-purge", 1L)).isEqualTo("PURGED");
    }

    private void insertLifecycle(String documentId, long publishedGeneration) {
        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id,
                    lifecycle_policy,
                    lifecycle_status,
                    generation,
                    attempt_count,
                    row_version,
                    retention_status,
                    published_generation,
                    next_generation,
                    access_level,
                    created_at,
                    updated_at
                ) VALUES (
                    ?, 'PERMANENT', 'READY', ?, 0, 0, 'ACTIVE', ?, ?, 1,
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
            String generationStatus
    ) {
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id,
                    generation,
                    generation_status,
                    generation_kind,
                    embedding_profile_id,
                    content_fingerprint,
                    physical_id_version,
                    access_level,
                    cleanup_required,
                    started_at,
                    failed_at,
                    retired_at
                ) VALUES (
                    ?, ?, ?, 'INGESTION', ?, ?, 2, 1, true,
                    clock_timestamp() - interval '1 hour',
                    CASE WHEN ? = 'FAILED'
                         THEN clock_timestamp() - interval '30 minutes'
                         ELSE NULL END,
                    CASE WHEN ? IN ('RETIRING', 'RETIRED')
                         THEN clock_timestamp() - interval '30 minutes'
                         ELSE NULL END
                )
                """,
                documentId,
                generation,
                generationStatus,
                profile.profileId(),
                "fp-" + documentId + "-" + generation,
                generationStatus,
                generationStatus
        );
    }

    private String status(String documentId, long generation) {
        return jdbc.queryForObject(
                """
                SELECT generation_status
                FROM knowledge_document_generation
                WHERE document_id = ?
                  AND generation = ?
                """,
                String.class,
                documentId,
                generation
        );
    }

    private String tombstoneStatus(String documentId, long generation) {
        return jdbc.queryForObject(
                """
                SELECT cleanup_status
                FROM knowledge_retired_generation
                WHERE document_id = ?
                  AND generation = ?
                  AND access_level = 1
                """,
                String.class,
                documentId,
                generation
        );
    }

    private long candidateLockKey(String documentId, long generation) {
        long documentHash = Integer.toUnsignedLong(documentId.hashCode());
        long generationHash = Integer.toUnsignedLong(Long.hashCode(generation));
        return (documentHash << 32) | generationHash;
    }
}
