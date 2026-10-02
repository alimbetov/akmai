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
class RetentionReingestionRaceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    static JdbcTemplate jdbc;
    static DocumentGenerationRepository generations;
    static RetentionClaimRepository claims;

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
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        generations = new DocumentGenerationRepository(jdbc, tx);
        claims = new RetentionClaimRepository(jdbc, tx);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void allocationInvalidatesDeletePendingClaimBeforeNewGenerationStarts() {
        long first = generations.allocate(
                "doc-1",
                RetentionPolicy.TTL,
                Instant.now().minusSeconds(60),
                null,
                "fingerprint-1"
        );
        publish("doc-1", first);

        RetentionClaim claim = claims.claimExpired(
                1,
                5,
                "pod-a",
                Duration.ofMinutes(10)
        ).getFirst();

        long second = generations.allocate(
                "doc-1",
                RetentionPolicy.PERMANENT,
                null,
                null,
                "fingerprint-2"
        );

        assertThat(second).isEqualTo(first + 1);
        Integer stillOwned = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                  AND claim_id = ?
                """,
                Integer.class,
                "doc-1",
                claim.claimId()
        );
        assertThat(stillOwned).isZero();
        assertThat(claims.release(claim)).isFalse();
    }

    private void publish(String documentId, long generation) {
        jdbc.update(
                """
                UPDATE knowledge_document_generation
                SET generation_status = 'PUBLISHED',
                    published_at = clock_timestamp()
                WHERE document_id = ?
                  AND generation = ?
                """,
                documentId,
                generation
        );
        jdbc.update(
                """
                UPDATE knowledge_document_lifecycle
                SET published_generation = ?,
                    generation = ?,
                    lifecycle_status = 'READY',
                    retention_status = 'ACTIVE'
                WHERE document_id = ?
                """,
                generation,
                generation,
                documentId
        );
    }
}
