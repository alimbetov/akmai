package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
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
class StaleIngestionRecoveryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres")
            )
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    static JdbcTemplate jdbc;
    static PGSimpleDataSource dataSource;
    static DocumentGenerationRepository generations;
    static EmbeddingProfile profile;

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
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        generations = new DocumentGenerationRepository(jdbc, tx);

        profile = new EmbeddingProfile(
                "ep-stale",
                "test",
                "deterministic",
                3,
                "COSINE_DISTANCE",
                "test",
                "stale",
                "akmai_vector",
                "p_stale",
                "NONE",
                (short) 1,
                Instant.parse("2026-10-02T00:00:00Z")
        );
        new EmbeddingProfileRepository(jdbc).save(profile);
        jdbc.update(
                """
                UPDATE knowledge_embedding_runtime
                SET active_profile_id = ?, migration_status = 'IDLE'
                WHERE singleton_id = 1
                """,
                profile.profileId()
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_ingestion_request");
        jdbc.update("DELETE FROM knowledge_embedding_migration_document");
        jdbc.update("DELETE FROM knowledge_embedding_migration");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
        jdbc.update(
                """
                UPDATE knowledge_embedding_runtime
                SET migration_status = 'IDLE',
                    migration_profile_id = NULL
                WHERE singleton_id = 1
                """
        );
    }

    @Test
    void staleRecoveryFailsOnlyOrdinaryIngestionGeneration() {
        long ingestion = generations.allocate(
                "doc-ingestion",
                RetentionPolicy.PERMANENT,
                null,
                profile.profileId(),
                "fp",
                1L
        );
        jdbc.update(
                """
                UPDATE knowledge_document_generation
                SET started_at = clock_timestamp() - interval '2 hours'
                WHERE document_id = 'doc-ingestion'
                  AND generation = ?
                """,
                ingestion
        );

        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id, lifecycle_policy, lifecycle_status,
                    generation, attempt_count, row_version,
                    ingestion_started_at, created_at, updated_at,
                    retention_status, published_generation, next_generation,
                    access_level
                ) VALUES (
                    'doc-migration', 'PERMANENT', 'READY',
                    1, 0, 0,
                    NULL, clock_timestamp(), clock_timestamp(),
                    'ACTIVE', NULL, 2, 1
                )
                """
        );
        UUID migrationId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO knowledge_embedding_migration (
                    migration_id, source_profile_id, target_profile_id,
                    migration_status
                ) VALUES (?, ?, ?, 'STAGING')
                """,
                migrationId,
                profile.profileId(),
                profile.profileId()
        );
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id, generation, generation_status,
                    generation_kind, migration_id, embedding_profile_id,
                    content_fingerprint, physical_id_version,
                    cleanup_required, started_at, access_level
                ) VALUES (
                    'doc-migration', 1, 'STAGING',
                    'REEMBEDDING', ?, ?, 'fp', 2,
                    false, clock_timestamp() - interval '2 hours', 1
                )
                """,
                migrationId,
                profile.profileId()
        );

        int recovered = generations.failStaleIngestionBatch(
                Duration.ofMinutes(30),
                10
        );

        assertThat(recovered).isEqualTo(1);
        assertThat(status("doc-ingestion", ingestion)).isEqualTo("FAILED");
        assertThat(status("doc-migration", 1L)).isEqualTo("STAGING");
    }

    @Test
    void activeIdempotencyClaimProtectsOldStagingGeneration() {
        long generation = generations.allocate(
                "doc-active",
                RetentionPolicy.PERMANENT,
                null,
                profile.profileId(),
                "fp-active",
                1L
        );
        jdbc.update(
                """
                UPDATE knowledge_document_generation
                SET started_at = clock_timestamp() - interval '2 hours'
                WHERE document_id = 'doc-active'
                  AND generation = ?
                """,
                generation
        );
        jdbc.update(
                """
                INSERT INTO knowledge_ingestion_request (
                    idempotency_key, document_id, request_fingerprint,
                    request_status, generation, claim_id, lease_until,
                    created_at, updated_at
                ) VALUES (
                    'active-key', 'doc-active', 'fp-active',
                    'IN_PROGRESS', ?, ?,
                    clock_timestamp() + interval '15 minutes',
                    clock_timestamp(), clock_timestamp()
                )
                """,
                generation,
                UUID.randomUUID()
        );

        int recovered = generations.failStaleIngestionBatch(
                Duration.ofMinutes(30),
                10
        );

        assertThat(recovered).isZero();
        assertThat(status("doc-active", generation)).isEqualTo("STAGING");
    }

    @Test
    void expiredIdempotencyClaimDoesNotProtectOldStagingGeneration() {
        long generation = generations.allocate(
                "doc-expired",
                RetentionPolicy.PERMANENT,
                null,
                profile.profileId(),
                "fp-expired",
                1L
        );
        jdbc.update(
                """
                UPDATE knowledge_document_generation
                SET started_at = clock_timestamp() - interval '2 hours'
                WHERE document_id = 'doc-expired'
                  AND generation = ?
                """,
                generation
        );
        jdbc.update(
                """
                INSERT INTO knowledge_ingestion_request (
                    idempotency_key, document_id, request_fingerprint,
                    request_status, generation, claim_id, lease_until,
                    created_at, updated_at
                ) VALUES (
                    'expired-key', 'doc-expired', 'fp-expired',
                    'IN_PROGRESS', ?, ?,
                    clock_timestamp() - interval '1 minute',
                    clock_timestamp(), clock_timestamp()
                )
                """,
                generation,
                UUID.randomUUID()
        );

        int recovered = generations.failStaleIngestionBatch(
                Duration.ofMinutes(30),
                10
        );

        assertThat(recovered).isEqualTo(1);
        assertThat(status("doc-expired", generation)).isEqualTo("FAILED");
    }

    @Test
    void lockedOldestStaleGenerationDoesNotStarveLaterCandidate()
            throws Exception {
        long lockedGeneration = generations.allocate(
                "doc-locked",
                RetentionPolicy.PERMANENT,
                null,
                profile.profileId(),
                "fp-locked",
                1L
        );
        long freeGeneration = generations.allocate(
                "doc-free",
                RetentionPolicy.PERMANENT,
                null,
                profile.profileId(),
                "fp-free",
                1L
        );
        jdbc.update(
                """
                UPDATE knowledge_document_generation
                SET started_at = CASE document_id
                    WHEN 'doc-locked' THEN clock_timestamp() - interval '3 hours'
                    ELSE clock_timestamp() - interval '2 hours'
                END
                WHERE document_id IN ('doc-locked', 'doc-free')
                """
        );

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    """
                    SELECT generation
                    FROM knowledge_document_generation
                    WHERE document_id = 'doc-locked'
                      AND generation = ?
                    FOR UPDATE
                    """
            )) {
                statement.setLong(1, lockedGeneration);
                statement.executeQuery();

                int recovered = generations.failStaleIngestionBatch(
                        Duration.ofMinutes(30),
                        1
                );

                assertThat(recovered).isEqualTo(1);
                assertThat(status("doc-free", freeGeneration)).isEqualTo("FAILED");
                assertThat(status("doc-locked", lockedGeneration))
                        .isEqualTo("STAGING");
            } finally {
                connection.rollback();
            }
        }
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
}
