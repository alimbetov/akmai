package kz.alimbetov.akmai.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import kz.alimbetov.akmai.config.ReembeddingProperties;
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
class ReembeddingLeaseHeartbeatSchedulerIntegrationTest {

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
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_embedding_migration_document");
        jdbc.update("DELETE FROM knowledge_embedding_migration");
    }

    @Test
    void schedulerHeartbeatExtendsLiveOwnedLeaseAndPreventsTakeover() {
        ReembeddingProperties properties = new ReembeddingProperties(
                false,
                Duration.ofSeconds(2),
                Duration.ofMillis(100),
                Duration.ofSeconds(1)
        );
        ReembeddingLeaseManager owner = new ReembeddingLeaseManager(
                jdbc,
                properties,
                "heartbeat-owner"
        );
        ReembeddingLeaseManager contender = new ReembeddingLeaseManager(
                jdbc,
                properties,
                "contender"
        );

        UUID migrationId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO knowledge_embedding_migration (
                    migration_id,
                    source_profile_id,
                    target_profile_id,
                    migration_status
                ) VALUES (?, 'source-profile', 'target-profile', 'STAGING')
                """,
                migrationId
        );
        owner.claimNew(migrationId);

        jdbc.update(
                """
                UPDATE knowledge_embedding_migration
                SET lease_until = clock_timestamp() + interval '250 milliseconds'
                WHERE migration_id = ?
                """,
                migrationId
        );

        ReembeddingLeaseHeartbeatScheduler scheduler =
                new ReembeddingLeaseHeartbeatScheduler(owner);
        scheduler.heartbeat();

        Long remainingMillis = jdbc.queryForObject(
                """
                SELECT floor(
                    extract(epoch FROM (lease_until - clock_timestamp())) * 1000
                )::bigint
                FROM knowledge_embedding_migration
                WHERE migration_id = ?
                """,
                Long.class,
                migrationId
        );

        assertThat(remainingMillis).isNotNull().isGreaterThan(1_000L);
        assertThat(contender.claimExpiredActive()).isEmpty();
    }

    @Test
    void schedulerHeartbeatDoesNotResurrectExpiredOwnedLease() {
        ReembeddingProperties properties = new ReembeddingProperties(
                false,
                Duration.ofSeconds(2),
                Duration.ofMillis(100),
                Duration.ofSeconds(1)
        );
        ReembeddingLeaseManager owner = new ReembeddingLeaseManager(
                jdbc,
                properties,
                "stale-owner"
        );
        ReembeddingLeaseManager contender = new ReembeddingLeaseManager(
                jdbc,
                properties,
                "new-owner"
        );

        UUID migrationId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO knowledge_embedding_migration (
                    migration_id,
                    source_profile_id,
                    target_profile_id,
                    migration_status
                ) VALUES (?, 'source-profile', 'target-profile', 'PREPARING')
                """,
                migrationId
        );
        owner.claimNew(migrationId);
        jdbc.update(
                """
                UPDATE knowledge_embedding_migration
                SET lease_until = clock_timestamp() - interval '1 second'
                WHERE migration_id = ?
                """,
                migrationId
        );

        ReembeddingLeaseHeartbeatScheduler scheduler =
                new ReembeddingLeaseHeartbeatScheduler(owner);
        scheduler.heartbeat();

        assertThat(contender.claimExpiredActive())
                .get()
                .satisfies(authority -> {
                    assertThat(authority.ownerId()).isEqualTo("new-owner");
                    assertThat(authority.fencingToken()).isEqualTo(2L);
                });
    }
}
