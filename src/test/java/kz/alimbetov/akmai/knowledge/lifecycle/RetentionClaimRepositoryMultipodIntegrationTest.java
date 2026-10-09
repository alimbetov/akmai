package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterEach;
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
class RetentionClaimRepositoryMultipodIntegrationTest {

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
    static RetentionClaimRepository repository;

    Connection blockingConnection;

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
        jdbc.update(
                """
                UPDATE knowledge_embedding_runtime
                SET migration_status = 'IDLE'
                WHERE singleton_id = 1
                """
        );
    }

    @AfterEach
    void releaseBlockingConnection() throws Exception {
        if (blockingConnection != null) {
            blockingConnection.rollback();
            blockingConnection.close();
            blockingConnection = null;
        }
    }

    @Test
    void lockedCandidateIsSkippedAndAnotherExpiredDocumentIsClaimed()
            throws Exception {
        seedPublished("doc-a", "clock_timestamp() - interval '2 hours'");
        seedPublished("doc-b", "clock_timestamp() - interval '1 hour'");

        blockingConnection = dataSource.getConnection();
        blockingConnection.setAutoCommit(false);
        try (var statement = blockingConnection.prepareStatement(
                """
                SELECT document_id
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-a'
                FOR UPDATE
                """
        )) {
            statement.executeQuery().close();
        }

        List<RetentionClaim> claims = repository.claimExpired(
                1,
                3,
                "pod-b",
                Duration.ofMinutes(10)
        );

        assertThat(claims).hasSize(1);
        assertThat(claims.getFirst().documentId()).isEqualTo("doc-b");
        assertThat(jdbc.queryForObject(
                """
                SELECT retention_status
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-b'
                """,
                String.class
        )).isEqualTo("DELETE_PENDING");
    }

    @Test
    void expiredLeaseCanBeReclaimedByAnotherWorker() {
        seedPublished("doc-expired", "clock_timestamp() - interval '2 hours'");
        jdbc.update(
                """
                UPDATE knowledge_document_lifecycle
                SET retention_status = 'DELETING',
                    lifecycle_status = 'DELETING',
                    claim_generation = 1,
                    claim_id = '11111111-1111-1111-1111-111111111111'::uuid,
                    claimed_by = 'pod-a',
                    claimed_at = clock_timestamp() - interval '20 minutes',
                    lease_until = clock_timestamp() - interval '1 minute'
                WHERE document_id = 'doc-expired'
                """
        );

        List<RetentionClaim> claims = repository.claimExpired(
                1,
                3,
                "pod-b",
                Duration.ofMinutes(10)
        );

        assertThat(claims).hasSize(1);
        RetentionClaim claim = claims.getFirst();
        assertThat(claim.documentId()).isEqualTo("doc-expired");
        assertThat(claim.workerId()).isEqualTo("pod-b");
        assertThat(jdbc.queryForObject(
                """
                SELECT claimed_by
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-expired'
                """,
                String.class
        )).isEqualTo("pod-b");
    }

    @Test
    void activeEmbeddingMigrationPausesClaiming() {
        seedPublished("doc-migration", "clock_timestamp() - interval '2 hours'");
        jdbc.update(
                """
                UPDATE knowledge_embedding_runtime
                SET migration_status = 'PREPARING'
                WHERE singleton_id = 1
                """
        );

        assertThat(repository.claimExpired(
                1,
                3,
                "pod-a",
                Duration.ofMinutes(10)
        )).isEmpty();
        assertThat(jdbc.queryForObject(
                """
                SELECT retention_status
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-migration'
                """,
                String.class
        )).isEqualTo("ACTIVE");
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
