package kz.alimbetov.akmai.knowledge.ingestion.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
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
class AsyncIngestionJobRepositoryIntegrationTest {

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
        jdbc.update("DELETE FROM knowledge_ingestion_job");
    }

    @Test
    void duplicateEventAndFingerprintReturnSameDurableJob() {
        AsyncIngestionJob first = repository.admit(candidate(
                "event-1",
                "fp-1"
        ));

        AsyncIngestionJob duplicateEvent = repository.admit(candidate(
                "event-1",
                "fp-1"
        ));
        AsyncIngestionJob duplicateFingerprint = repository.admit(candidate(
                "event-2",
                "fp-1"
        ));

        assertThat(duplicateEvent.ingestionId()).isEqualTo(first.ingestionId());
        assertThat(duplicateFingerprint.ingestionId()).isEqualTo(first.ingestionId());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_ingestion_job",
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void reusedEventWithDifferentFingerprintIsConflict() {
        repository.admit(candidate("event-1", "fp-1"));

        assertThatThrownBy(() -> repository.admit(candidate(
                "event-1",
                "fp-2"
        )))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void claimUsesLeaseFenceAndStaleOwnerCannotFinalize() {
        AsyncIngestionJob admitted = repository.admit(candidate(
                "event-claim",
                "fp-claim"
        ));

        AsyncIngestionClaim claim = repository.claimEligible(
                "worker-a",
                1,
                Duration.ofMinutes(2)
        ).getFirst();

        assertThat(claim.ingestionId()).isEqualTo(admitted.ingestionId());
        assertThat(claim.leaseVersion()).isEqualTo(1L);
        assertThat(repository.renew(claim, Duration.ofMinutes(2))).isTrue();

        AsyncIngestionClaim stale = new AsyncIngestionClaim(
                claim.ingestionId(),
                claim.leaseOwner(),
                claim.leaseVersion() + 1,
                claim.job()
        );
        assertThat(repository.markIngested(
                stale,
                1L,
                3,
                "profile"
        )).isFalse();

        assertThat(repository.markIngested(
                claim,
                1L,
                3,
                "profile"
        )).isTrue();
        assertThat(repository.find(admitted.ingestionId()).orElseThrow().status())
                .isEqualTo(AsyncIngestionJobStatus.INGESTED);
    }

    @Test
    void retryWaitIsNotClaimableUntilDueAndDoesNotConsumeBudgetWhenRequested() {
        repository.admit(candidate("event-retry", "fp-retry"));
        AsyncIngestionClaim claim = repository.claimEligible(
                "worker-a",
                1,
                Duration.ofMinutes(2)
        ).getFirst();

        assertThat(repository.markRetry(
                claim,
                Instant.now().plusSeconds(60),
                false,
                "RETRYABLE",
                "INGESTION_IN_PROGRESS",
                "still running"
        )).isTrue();

        assertThat(repository.claimEligible(
                "worker-b",
                1,
                Duration.ofMinutes(2)
        )).isEmpty();

        AsyncIngestionJob waiting = repository.find(claim.ingestionId())
                .orElseThrow();
        assertThat(waiting.failureCount()).isZero();
        assertThat(waiting.status()).isEqualTo(AsyncIngestionJobStatus.RETRY_WAIT);

        jdbc.update(
                """
                UPDATE knowledge_ingestion_job
                SET next_attempt_at = clock_timestamp() - interval '1 second'
                WHERE ingestion_id = ?
                """,
                claim.ingestionId()
        );

        AsyncIngestionClaim reclaimed = repository.claimEligible(
                "worker-b",
                1,
                Duration.ofMinutes(2)
        ).getFirst();
        assertThat(reclaimed.leaseVersion()).isEqualTo(2L);
        assertThat(reclaimed.job().attemptCount()).isEqualTo(2);
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
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
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
