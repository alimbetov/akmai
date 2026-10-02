package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
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

@Testcontainers
class RetentionLeaseClockIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    static JdbcTemplate jdbc;
    static RetentionClaimRepository claims;

    @BeforeAll
    static void setup() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(ds);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();

        jdbc = new JdbcTemplate(ds);
        claims = new RetentionClaimRepository(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(ds))
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
        jdbc.update(
                "UPDATE knowledge_embedding_runtime SET migration_status = 'IDLE', migration_profile_id = NULL WHERE singleton_id = 1"
        );
    }

    @Test
    void leaseUsesDatabaseClockAndExpiredWorkerCannotMarkFailed() {
        seedExpiredDocument();

        Instant before = dbNow();
        Duration lease = Duration.ofMinutes(5);
        RetentionClaim claim = claims.claimExpired(
                1, 5, "worker-1", lease
        ).getFirst();
        Instant after = dbNow();

        assertThat(claim.leaseUntil())
                .isAfterOrEqualTo(before.plus(lease))
                .isBeforeOrEqualTo(after.plus(lease).plusSeconds(1));

        jdbc.update(
                """
                UPDATE knowledge_document_lifecycle
                SET lease_until = clock_timestamp() - interval '1 second'
                WHERE document_id = 'doc-ttl'
                """
        );

        assertThat(claims.markFailed(claim, "late failure")).isFalse();
        assertThat(jdbc.queryForObject(
                """
                SELECT retention_status
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-ttl'
                """,
                String.class
        )).isEqualTo("DELETE_PENDING");
        assertThat(jdbc.queryForObject(
                """
                SELECT attempt_count
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-ttl'
                """,
                Integer.class
        )).isZero();
    }

    private void seedExpiredDocument() {
        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id, lifecycle_policy, lifecycle_status,
                    generation, attempt_count, row_version,
                    created_at, updated_at, retention_status,
                    published_generation, next_generation, expires_at,
                    access_level
                ) VALUES (
                    'doc-ttl', 'TTL', 'READY',
                    1, 0, 0,
                    clock_timestamp(), clock_timestamp(),
                    'ACTIVE', 1, 2,
                    clock_timestamp() - interval '1 minute', 1
                )
                """
        );
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id, generation, generation_status,
                    generation_kind, content_fingerprint,
                    physical_id_version, cleanup_required,
                    started_at, published_at, access_level
                ) VALUES (
                    'doc-ttl', 1, 'PUBLISHED',
                    'INGESTION', 'fp', 2, false,
                    clock_timestamp() - interval '1 hour',
                    clock_timestamp() - interval '30 minutes', 1
                )
                """
        );
    }

    private Instant dbNow() {
        return jdbc.queryForObject(
                "SELECT clock_timestamp()",
                java.sql.Timestamp.class
        ).toInstant();
    }
}
