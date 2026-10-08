package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

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
class RetentionBacklogIntegrationTest {

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
    static RetentionClaimRepository repository;

    @BeforeAll
    static void migrate() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
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
        repository = new RetentionClaimRepository(
                jdbc,
                new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)
                )
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void backlogReflectsEligibleLifecycleRowsAndExcludesStagingDocuments() {
        seedPublished("expired", "clock_timestamp() - interval '1 hour'");
        seedPublished("future", "clock_timestamp() + interval '1 hour'");
        seedPublished("staging", "clock_timestamp() - interval '2 hours'");
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id, generation, generation_status,
                    generation_kind, physical_id_version,
                    cleanup_required, started_at, access_level
                ) VALUES (
                    'staging', 2, 'STAGING',
                    'INGESTION', 2,
                    false, clock_timestamp(), 1
                )
                """
        );

        assertThat(repository.countEligibleBacklog(3)).isEqualTo(1);

        jdbc.update(
                """
                UPDATE knowledge_document_lifecycle
                SET expires_at = clock_timestamp() - interval '1 minute'
                WHERE document_id = 'future'
                """
        );

        assertThat(repository.countEligibleBacklog(3)).isEqualTo(2);
    }

    @Test
    void expiredOwnedClaimBecomesBacklogButLiveLeaseDoesNot() {
        seedPublished("expired-claim", "clock_timestamp() - interval '2 hours'");
        seedPublished("live-claim", "clock_timestamp() - interval '2 hours'");

        jdbc.update(
                """
                UPDATE knowledge_document_lifecycle
                SET retention_status = 'DELETE_PENDING',
                    lifecycle_status = 'DELETE_PENDING',
                    claim_generation = 1,
                    claim_id = '11111111-1111-1111-1111-111111111111'::uuid,
                    claimed_by = 'pod-a',
                    claimed_at = clock_timestamp() - interval '20 minutes',
                    lease_until = clock_timestamp() - interval '1 minute'
                WHERE document_id = 'expired-claim'
                """
        );
        jdbc.update(
                """
                UPDATE knowledge_document_lifecycle
                SET retention_status = 'DELETE_PENDING',
                    lifecycle_status = 'DELETE_PENDING',
                    claim_generation = 1,
                    claim_id = '22222222-2222-2222-2222-222222222222'::uuid,
                    claimed_by = 'pod-b',
                    claimed_at = clock_timestamp(),
                    lease_until = clock_timestamp() + interval '10 minutes'
                WHERE document_id = 'live-claim'
                """
        );

        assertThat(repository.countEligibleBacklog(3)).isEqualTo(1);
    }

    @Test
    void retryExhaustedFailureIsExcludedFromWorkBacklogButCountedSeparately() {
        seedPublished("retryable", "clock_timestamp() - interval '2 hours'");
        seedPublished("dead-letter", "clock_timestamp() - interval '2 hours'");

        jdbc.update(
                """
                UPDATE knowledge_document_lifecycle
                SET retention_status = 'DELETE_FAILED',
                    lifecycle_status = 'DELETE_FAILED',
                    attempt_count = 2,
                    last_error = 'retryable failure'
                WHERE document_id = 'retryable'
                """
        );
        jdbc.update(
                """
                UPDATE knowledge_document_lifecycle
                SET retention_status = 'DELETE_FAILED',
                    lifecycle_status = 'DELETE_FAILED',
                    attempt_count = 3,
                    last_error = 'retry limit exhausted'
                WHERE document_id = 'dead-letter'
                """
        );

        assertThat(repository.countEligibleBacklog(3)).isEqualTo(1);
        assertThat(repository.countRetryExhausted(3)).isEqualTo(1);
    }

    private void seedPublished(String documentId, String expiresExpression) {
        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id, lifecycle_policy, lifecycle_status,
                    generation, attempt_count, row_version,
                    created_at, updated_at, retention_status,
                    published_generation, next_generation, expires_at,
                    access_level
                ) VALUES (
                    ?, 'TTL', 'READY',
                    1, 0, 0,
                    clock_timestamp(), clock_timestamp(), 'ACTIVE',
                    1, 2, %s, 1
                )
                """.formatted(expiresExpression),
                documentId
        );
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id, generation, generation_status,
                    generation_kind, physical_id_version,
                    cleanup_required, started_at, published_at, access_level
                ) VALUES (
                    ?, 1, 'PUBLISHED',
                    'INGESTION', 2,
                    false, clock_timestamp() - interval '1 day',
                    clock_timestamp() - interval '12 hours', 1
                )
                """,
                documentId
        );
    }
}
