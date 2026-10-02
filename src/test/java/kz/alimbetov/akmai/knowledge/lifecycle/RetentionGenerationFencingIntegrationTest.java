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
class RetentionGenerationFencingIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    static JdbcTemplate jdbc;
    static PostgresDocumentLifecycleRepository lifecycle;

    @BeforeAll
    static void migrate() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();

        jdbc = new JdbcTemplate(dataSource);
        lifecycle = new PostgresDocumentLifecycleRepository(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void generationNClaimCannotMutateGenerationNPlusOne() {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        long generationN = lifecycle.beginIngestion(
                "doc-race",
                RetentionPolicy.TTL,
                now.minusSeconds(60),
                1L
        );
        assertThat(lifecycle.publishIngestion("doc-race", generationN, now.minusSeconds(30)))
                .isTrue();

        RetentionClaim staleClaim = lifecycle.claimExpired(
                now, 1, 5, "pod-retention", Duration.ofMinutes(10)
        ).getFirst();

        long generationNPlusOne = lifecycle.beginIngestion(
                "doc-race",
                RetentionPolicy.PERMANENT,
                null,
                1L
        );
        assertThat(lifecycle.publishIngestion("doc-race", generationNPlusOne, now.plusSeconds(1)))
                .isTrue();

        assertThat(generationNPlusOne).isEqualTo(generationN + 1);
        assertThat(lifecycle.isCurrentClaim(staleClaim, now.plusSeconds(1))).isFalse();
        assertThat(lifecycle.markDeleting(staleClaim, now.plusSeconds(1))).isFalse();
        assertThat(lifecycle.markFailed(
                staleClaim, now.plusSeconds(1), "stale retention worker"
        )).isFalse();

        DocumentLifecycle current = lifecycle.findByDocumentId("doc-race").orElseThrow();
        assertThat(current.generation()).isEqualTo(generationNPlusOne);
        assertThat(current.status()).isEqualTo(LifecycleStatus.READY);
        assertThat(current.policy()).isEqualTo(RetentionPolicy.PERMANENT);
    }

    @Test
    void staleIngestionIsRecoverableAndNextAttemptAdvancesGeneration() {
        Instant started = Instant.parse("2026-10-01T09:00:00Z");
        long abandoned = lifecycle.beginIngestion(
                "doc-crash",
                RetentionPolicy.PERMANENT,
                null,
                1L
        );
        jdbc.update(
                "UPDATE knowledge_document_lifecycle SET ingestion_started_at = ? WHERE document_id = ?",
                java.sql.Timestamp.from(started),
                "doc-crash"
        );

        Instant recovery = Instant.parse("2026-10-01T10:00:00Z");
        Instant staleBefore = recovery.minus(Duration.ofMinutes(10));
        assertThat(lifecycle.findStaleIngestionDocumentIds(staleBefore, 10))
                .containsExactly("doc-crash");
        assertThat(lifecycle.failStaleIngestion(
                "doc-crash",
                staleBefore,
                recovery,
                "abandoned ingestion exceeded recovery timeout"
        )).isTrue();
        assertThat(lifecycle.findByDocumentId("doc-crash").orElseThrow().status())
                .isEqualTo(LifecycleStatus.INGEST_FAILED);

        long retry = lifecycle.beginIngestion(
                "doc-crash",
                RetentionPolicy.PERMANENT,
                null,
                1L
        );
        assertThat(retry).isEqualTo(abandoned + 1);
        assertThat(lifecycle.publishIngestion("doc-crash", retry, recovery.plusSeconds(1)))
                .isTrue();
        assertThat(lifecycle.findByDocumentId("doc-crash").orElseThrow().status())
                .isEqualTo(LifecycleStatus.READY);
    }
}
