package kz.alimbetov.akmai.knowledge.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentGenerationRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
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
class IdempotencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static IngestionIdempotencyRepository repository;
    static DocumentGenerationRepository generations;

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
        tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        repository = new IngestionIdempotencyRepository(
                jdbc,
                tx,
                new ObjectMapper()
        );
        generations = new DocumentGenerationRepository(jdbc, tx);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_ingestion_request");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void expiredClaimRecoversAlreadyPublishedGenerationInsteadOfAllocatingAgain() {
        var first = repository.claim(
                "key-1",
                "doc-1",
                "fingerprint",
                Duration.ofMinutes(10)
        );
        assertThat(first.status())
                .isEqualTo(IngestionIdempotencyRepository.ClaimResult.Status.CLAIMED);

        long generation = generations.allocate(
                "doc-1",
                RetentionPolicy.PERMANENT,
                null,
                null,
                "content-fingerprint"
        );
        repository.attachGeneration(first.context(), generation);

        jdbc.update(
                """
                UPDATE knowledge_document_generation
                SET generation_status = 'PUBLISHED',
                    published_at = clock_timestamp()
                WHERE document_id = ?
                  AND generation = ?
                """,
                "doc-1",
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
                "doc-1"
        );
        jdbc.update(
                """
                UPDATE knowledge_ingestion_request
                SET lease_until = clock_timestamp() - interval '1 second'
                WHERE idempotency_key = ?
                """,
                "key-1"
        );

        var reclaimed = repository.claim(
                "key-1",
                "doc-1",
                "fingerprint",
                Duration.ofMinutes(10)
        );

        assertThat(reclaimed.status())
                .isEqualTo(IngestionIdempotencyRepository.ClaimResult.Status.REPLAY);
        assertThat(reclaimed.response().documentId()).isEqualTo("doc-1");
        assertThat(reclaimed.response().chunkCount()).isZero();

        assertThat(jdbc.queryForObject(
                """
                SELECT request_status
                FROM knowledge_ingestion_request
                WHERE idempotency_key = ?
                """,
                String.class,
                "key-1"
        )).isEqualTo("SUCCEEDED");

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_document_generation
                WHERE document_id = ?
                """,
                Integer.class,
                "doc-1"
        )).isEqualTo(1);
    }
}
