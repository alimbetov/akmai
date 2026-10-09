package kz.alimbetov.akmai.knowledge.ingestion.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kz.alimbetov.akmai.knowledge.idempotency.IdempotencyConflictException;
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
class AsyncIngestionSourceVersionIntegrationTest {

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
    static AsyncIngestionJobRepository repository;

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
        repository = new AsyncIngestionJobRepository(
                jdbc,
                new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)
                )
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_ingestion_job_event");
        jdbc.update("DELETE FROM knowledge_ingestion_job");
    }

    @Test
    void sameImmutableSourceVersionWithDifferentFingerprintIsRejected() {
        AsyncIngestionJob first = repository.admit(candidate(
                "event-1",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        ));

        assertThatThrownBy(() -> repository.admit(candidate(
                "event-2",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        )))
                .isInstanceOfSatisfying(
                        IdempotencyConflictException.class,
                        conflict -> assertThat(conflict.code())
                                .isEqualTo("ASYNC_SOURCE_VERSION_REUSE")
                );

        assertThat(repository.findBySourceVersion("file-1", "1").orElseThrow()
                .ingestionId()).isEqualTo(first.ingestionId());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_ingestion_job",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_ingestion_job_event",
                Integer.class
        )).isEqualTo(1);
    }

    private AsyncIngestionJob candidate(String eventId, String fingerprint) {
        UUID id = UUID.randomUUID();
        return new AsyncIngestionJob(
                id,
                1,
                eventId,
                null,
                fingerprint,
                "async-ingestion:" + id,
                "doc-1",
                1L,
                "FILE",
                "file-1",
                "1",
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                fingerprint,
                "INLINE",
                "{}",
                null,
                AsyncIngestionJobStatus.ACCEPTED,
                0,
                0,
                null,
                null,
                null,
                0L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
