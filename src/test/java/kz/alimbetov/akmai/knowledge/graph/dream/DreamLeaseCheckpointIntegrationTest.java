package kz.alimbetov.akmai.knowledge.graph.dream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class DreamLeaseCheckpointIntegrationTest {

    private static final String POLICY_FINGERPRINT = "c".repeat(64);

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
    static AdaptiveGraphProperties properties;

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
        properties = mock(AdaptiveGraphProperties.class);
        AdaptiveGraphProperties.Dream dream =
                mock(AdaptiveGraphProperties.Dream.class);
        when(properties.dream()).thenReturn(dream);
        when(dream.leaseDuration()).thenReturn(Duration.ofSeconds(30));
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM adaptive_graph_dream_run");
        jdbc.update("DELETE FROM adaptive_graph_dream_checkpoint");
        jdbc.update("DELETE FROM adaptive_graph_dream_lease");
    }

    @Test
    void healthyOwnerCannotBeStolenAndExpiredTakeoverIncrementsToken() {
        DreamLeaseManager podA = manager("pod-a");
        DreamLeaseManager podB = manager("pod-b");

        DreamLeaseManager.Authority first = podA.tryAcquire(
                1,
                POLICY_FINGERPRINT
        ).orElseThrow();
        assertThat(first.fencingToken()).isEqualTo(1);

        assertThat(podB.tryAcquire(1, POLICY_FINGERPRINT)).isEmpty();

        jdbc.update(
                """
                UPDATE adaptive_graph_dream_lease
                SET lease_until = clock_timestamp() - interval '1 second'
                WHERE graph_version = 1
                  AND semantic_policy_fingerprint = ?
                """,
                POLICY_FINGERPRINT
        );

        DreamLeaseManager.Authority second = podB.tryAcquire(
                1,
                POLICY_FINGERPRINT
        ).orElseThrow();
        assertThat(second.fencingToken()).isEqualTo(2);
        assertThat(second.ownerId()).isEqualTo("pod-b");

        assertThatThrownBy(() -> podA.renew(first))
                .isInstanceOf(DreamLeaseManager.LostDreamAuthorityException.class);
        podB.renew(second);
        assertThat(podB.isOwned(second)).isTrue();
        assertThat(podA.isOwned(first)).isFalse();
    }

    @Test
    void staleOwnerCannotAdvanceCheckpointAfterTakeover() {
        DreamLeaseManager podA = manager("pod-a");
        DreamLeaseManager podB = manager("pod-b");
        DreamCheckpointRepository checkpoint =
                new DreamCheckpointRepository(jdbc);

        DreamLeaseManager.Authority first = podA.tryAcquire(
                1,
                POLICY_FINGERPRINT
        ).orElseThrow();
        checkpoint.initialize(first);

        jdbc.update(
                """
                UPDATE adaptive_graph_dream_lease
                SET lease_until = clock_timestamp() - interval '1 second'
                WHERE graph_version = 1
                  AND semantic_policy_fingerprint = ?
                """,
                POLICY_FINGERPRINT
        );
        DreamLeaseManager.Authority second = podB.tryAcquire(
                1,
                POLICY_FINGERPRINT
        ).orElseThrow();
        checkpoint.initialize(second);

        DreamCheckpointRepository.Watermark next =
                new DreamCheckpointRepository.Watermark(
                        java.time.Instant.parse("2026-10-09T00:00:00Z"),
                        1,
                        "doc-a",
                        1,
                        "chunk-a"
                );

        assertThatThrownBy(() -> checkpoint.advanceFastWatermark(
                first,
                null,
                next,
                UUID.randomUUID()
        )).isInstanceOf(DreamLeaseManager.LostDreamAuthorityException.class);

        UUID runId = UUID.randomUUID();
        checkpoint.advanceFastWatermark(second, null, next, runId);
        DreamCheckpointRepository.Checkpoint stored = checkpoint.find(
                1,
                POLICY_FINGERPRINT
        ).orElseThrow();
        assertThat(stored.fastWatermark()).contains(next);
        assertThat(stored.lastSuccessfulRunId()).isEqualTo(runId);
        assertThat(stored.fencingToken()).isEqualTo(second.fencingToken());
    }

    @Test
    void rescanCursorPersistsAcrossRepositoryRestartAndStaleOwnerCannotMoveIt() {
        DreamLeaseManager podA = manager("pod-a");
        DreamLeaseManager podB = manager("pod-b");
        DreamCheckpointRepository checkpoint =
                new DreamCheckpointRepository(jdbc);
        DreamRescanCheckpointRepository firstRepository =
                new DreamRescanCheckpointRepository(jdbc);

        DreamLeaseManager.Authority first = podA.tryAcquire(
                1,
                POLICY_FINGERPRINT
        ).orElseThrow();
        checkpoint.initialize(first);

        DreamRescanCursor cursor = new DreamRescanCursor(
                1,
                "doc-a",
                1,
                "chunk-a"
        );
        firstRepository.advance(
                first,
                null,
                cursor,
                UUID.randomUUID()
        );

        DreamRescanCheckpointRepository restartedRepository =
                new DreamRescanCheckpointRepository(jdbc);
        assertThat(checkpoint.find(1, POLICY_FINGERPRINT))
                .map(DreamCheckpointRepository.Checkpoint::rescanCursor)
                .contains(cursor.encode());

        jdbc.update(
                """
                UPDATE adaptive_graph_dream_lease
                SET lease_until = clock_timestamp() - interval '1 second'
                WHERE graph_version = 1
                  AND semantic_policy_fingerprint = ?
                """,
                POLICY_FINGERPRINT
        );
        DreamLeaseManager.Authority second = podB.tryAcquire(
                1,
                POLICY_FINGERPRINT
        ).orElseThrow();
        checkpoint.initialize(second);

        assertThatThrownBy(() -> restartedRepository.advance(
                first,
                cursor.encode(),
                null,
                UUID.randomUUID()
        )).isInstanceOf(DreamLeaseManager.LostDreamAuthorityException.class);

        restartedRepository.advance(
                second,
                cursor.encode(),
                null,
                UUID.randomUUID()
        );
        assertThat(checkpoint.find(1, POLICY_FINGERPRINT)
                .orElseThrow()
                .rescanCursor()).isNull();
    }

    private DreamLeaseManager manager(String ownerId) {
        return new DreamLeaseManager(jdbc, properties, ownerId);
    }
}
