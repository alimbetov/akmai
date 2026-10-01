package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterAll;
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
class PostgresDocumentLifecycleRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    static JdbcTemplate jdbcTemplate;
    static PostgresDocumentLifecycleRepository repository;
    static ExecutorService executor;

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

        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new PostgresDocumentLifecycleRepository(
                jdbcTemplate,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterAll
    static void shutdown() {
        executor.shutdownNow();
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void claimsOnlyExpiredTtlDocuments() {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        repository.activate("expired", RetentionPolicy.TTL, now.minusSeconds(60));
        repository.activate("future", RetentionPolicy.TTL, now.plusSeconds(60));
        repository.activate("permanent", RetentionPolicy.PERMANENT, null);

        List<RetentionClaim> claims = repository.claimExpired(now, 10, 5);

        assertThat(claims)
                .extracting(RetentionClaim::documentId)
                .containsExactly("expired");
        assertThat(repository.findByDocumentId("future").orElseThrow().status())
                .isEqualTo(LifecycleStatus.READY);
        assertThat(repository.findByDocumentId("permanent").orElseThrow().status())
                .isEqualTo(LifecycleStatus.READY);
    }

    @Test
    void failedClaimIsRetryableUntilRetryLimit() {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        repository.activate("retry", RetentionPolicy.TTL, now.minusSeconds(60));

        RetentionClaim first = repository.claimExpired(now, 1, 2).getFirst();
        assertThat(repository.markDeleting(first, now)).isTrue();
        assertThat(repository.markFailed(first, now, "temporary\nvector failure")).isTrue();

        RetentionClaim second = repository.claimExpired(now, 1, 2).getFirst();
        assertThat(repository.markDeleting(second, now)).isTrue();
        assertThat(repository.markFailed(second, now, "still failing")).isTrue();

        assertThat(repository.claimExpired(now, 1, 2)).isEmpty();
        DocumentLifecycle lifecycle =
                repository.findByDocumentId("retry").orElseThrow();
        assertThat(lifecycle.status()).isEqualTo(LifecycleStatus.DELETE_FAILED);
        assertThat(lifecycle.attemptCount()).isEqualTo(2);
        assertThat(lifecycle.lastError()).doesNotContain("\n");
    }

    @Test
    void reingestionInvalidatesOldRetentionClaim() {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        long firstGeneration = repository.activate(
                "reingested",
                RetentionPolicy.TTL,
                now.minusSeconds(60)
        );
        RetentionClaim stale = repository.claimExpired(now, 1, 5).getFirst();

        long secondGeneration = repository.activate(
                "reingested",
                RetentionPolicy.PERMANENT,
                null
        );

        assertThat(firstGeneration).isEqualTo(1);
        assertThat(secondGeneration).isEqualTo(2);
        assertThat(repository.isCurrentClaim(stale, now)).isFalse();
        assertThat(repository.markDeleting(stale, now)).isFalse();
        assertThat(repository.findByDocumentId("reingested").orElseThrow().status())
                .isEqualTo(LifecycleStatus.READY);
    }

    @Test
    void concurrentClaimsAreDisjoint() {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        for (int i = 0; i < 10; i++) {
            repository.activate(
                    "doc-" + i,
                    RetentionPolicy.TTL,
                    now.minusSeconds(60 + i)
            );
        }

        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<List<RetentionClaim>> first =
                CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return repository.claimExpired(now, 5, 5);
                }, executor);
        CompletableFuture<List<RetentionClaim>> second =
                CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return repository.claimExpired(now, 5, 5);
                }, executor);

        start.countDown();
        List<String> firstIds = first.join().stream()
                .map(RetentionClaim::documentId)
                .toList();
        List<String> secondIds = second.join().stream()
                .map(RetentionClaim::documentId)
                .toList();

        assertThat(firstIds).hasSize(5);
        assertThat(secondIds).hasSize(5);
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        assertThat(repository.claimExpired(now, 10, 5)).isEmpty();
    }

    @Test
    void stateTransitionsRequireMatchingGeneration() {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        repository.activate("stateful", RetentionPolicy.TTL, now.minusSeconds(1));
        RetentionClaim claim = repository.claimExpired(now, 1, 5).getFirst();

        assertThat(repository.markDeleting(claim, now)).isTrue();
        assertThat(repository.markDeleted(claim, now.plusSeconds(1))).isTrue();

        DocumentLifecycle lifecycle =
                repository.findByDocumentId("stateful").orElseThrow();
        assertThat(lifecycle.status()).isEqualTo(LifecycleStatus.DELETED);
        assertThat(lifecycle.claimGeneration()).isNull();
        assertThat(lifecycle.deletedAt()).isEqualTo(now.plusSeconds(1));
    }

    @Test
    void expiredLeaseCanBeReclaimedByAnotherPod() {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        repository.activate("crashed", RetentionPolicy.TTL, now.minusSeconds(60));

        RetentionClaim podA = repository.claimExpired(
                now, 1, 5, "pod-a", Duration.ofMinutes(10)
        ).getFirst();

        assertThat(repository.claimExpired(
                now.plusSeconds(300), 1, 5, "pod-b", Duration.ofMinutes(10)
        )).isEmpty();

        RetentionClaim podB = repository.claimExpired(
                now.plusSeconds(601), 1, 5, "pod-b", Duration.ofMinutes(10)
        ).getFirst();

        assertThat(podB.documentId()).isEqualTo("crashed");
        assertThat(podB.workerId()).isEqualTo("pod-b");
        assertThat(repository.isCurrentClaim(podA, now.plusSeconds(601))).isFalse();
        assertThat(repository.isCurrentClaim(podB, now.plusSeconds(601))).isTrue();
        assertThat(repository.findByDocumentId("crashed").orElseThrow().attemptCount())
                .isEqualTo(1);
    }

    @Test
    void leaseCanBeRenewedOnlyByCurrentOwner() {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        repository.activate("long-job", RetentionPolicy.TTL, now.minusSeconds(60));
        RetentionClaim claim = repository.claimExpired(
                now, 1, 5, "pod-a", Duration.ofMinutes(10)
        ).getFirst();

        RetentionClaim impostor = new RetentionClaim(
                claim.documentId(),
                claim.generation(),
                UUID.randomUUID(),
                "pod-b",
                claim.leaseUntil()
        );

        assertThat(repository.renewLease(
                impostor, now.plusSeconds(60), Duration.ofMinutes(10)
        )).isFalse();
        assertThat(repository.renewLease(
                claim, now.plusSeconds(60), Duration.ofMinutes(10)
        )).isTrue();
    }

    @Test
    void reclaimedClaimInvalidatesPreviousTokenEvenForSamePod() {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        repository.activate("same-pod", RetentionPolicy.TTL, now.minusSeconds(60));

        RetentionClaim first = repository.claimExpired(
                now, 1, 5, "pod-a", Duration.ofMinutes(10)
        ).getFirst();
        RetentionClaim reclaimed = repository.claimExpired(
                now.plusSeconds(601), 1, 5, "pod-a", Duration.ofMinutes(10)
        ).getFirst();

        assertThat(reclaimed.claimId()).isNotEqualTo(first.claimId());
        assertThat(repository.isCurrentClaim(first, now.plusSeconds(601))).isFalse();
        assertThat(repository.isCurrentClaim(reclaimed, now.plusSeconds(601))).isTrue();
        assertThat(repository.markDeleting(first, now.plusSeconds(601))).isFalse();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting claim", exception);
        }
    }
}
