package kz.alimbetov.akmai.runtimeconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kz.alimbetov.akmai.config.AdaptiveGraphCompetitionProperties;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
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
class PostgresAppParameterRepositoryIntegrationTest {

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
    static TransactionTemplate tx;
    static PostgresAppParameterRepository repository;
    static AppParameterService service;

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
        repository = new PostgresAppParameterRepository(jdbc);
        service = new AppParameterService(
                repository,
                mock(AdaptiveGraphProperties.class),
                mock(AdaptiveGraphCompetitionProperties.class),
                new AppParameterProperties(
                        Duration.ofSeconds(2),
                        64
                )
        );
    }

    @BeforeEach
    void reset() {
        jdbc.update(
                """
                UPDATE app_parameter
                SET parameter_value = 'false',
                    row_version = 0,
                    updated_by = 'test',
                    updated_at = clock_timestamp()
                """
        );
    }

    @Test
    void migrationSeedsCompleteBooleanRegistry() {
        List<AppParameter> parameters = tx.execute(status ->
                repository.lockAll(keys())
        );

        assertThat(parameters).isNotNull();
        assertThat(parameters)
                .hasSize(AppParameterKey.values().length)
                .extracting(AppParameter::key)
                .containsExactlyInAnyOrderElementsOf(keys());
        assertThat(parameters)
                .allSatisfy(parameter -> {
                    assertThat(parameter.type())
                            .isEqualTo(AppParameterType.BOOLEAN);
                    assertThat(parameter.booleanValue()).isFalse();
                    assertThat(parameter.version()).isZero();
                });
    }

    @Test
    void optimisticUpdateRejectsStaleVersion() {
        AppParameterKey key =
                AppParameterKey.ADAPTIVE_GRAPH_SHADOW_EXPANSION_ENABLED;

        AppParameter updated = repository.updateBoolean(
                key.key(),
                true,
                0L,
                "integration-test"
        ).orElseThrow();

        assertThat(updated.booleanValue()).isTrue();
        assertThat(updated.version()).isEqualTo(1L);
        assertThat(updated.updatedBy()).isEqualTo("integration-test");

        assertThat(repository.updateBoolean(
                key.key(),
                false,
                0L,
                "stale-writer"
        )).isEmpty();

        AppParameter persisted = repository.find(key.key())
                .orElseThrow();
        assertThat(persisted.booleanValue()).isTrue();
        assertThat(persisted.version()).isEqualTo(1L);
    }

    @Test
    void concurrentDependencyUpdatesCannotCommitInvalidGraphState()
            throws Exception {
        AppParameterKey maintenance =
                AppParameterKey.ADAPTIVE_GRAPH_MAINTENANCE_ENABLED;
        AppParameterKey expansion =
                AppParameterKey.ADAPTIVE_GRAPH_EXPANSION_ENABLED;

        AppParameter enabledMaintenance = repository.updateBoolean(
                maintenance.key(),
                true,
                0L,
                "setup"
        ).orElseThrow();
        assertThat(enabledMaintenance.version()).isEqualTo(1L);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> enableExpansion = executor.submit(() -> {
                ready.countDown();
                start.await();
                return tryUpdate(
                        expansion,
                        true,
                        0L,
                        "enable-expansion"
                );
            });
            Future<Boolean> disableMaintenance = executor.submit(() -> {
                ready.countDown();
                start.await();
                return tryUpdate(
                        maintenance,
                        false,
                        1L,
                        "disable-maintenance"
                );
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            boolean expansionSucceeded =
                    enableExpansion.get(10, TimeUnit.SECONDS);
            boolean maintenanceDisableSucceeded =
                    disableMaintenance.get(10, TimeUnit.SECONDS);

            assertThat(List.of(
                    expansionSucceeded,
                    maintenanceDisableSucceeded
            )).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }

        boolean maintenanceEnabled = repository.find(
                maintenance.key()
        ).orElseThrow().booleanValue();
        boolean expansionEnabled = repository.find(
                expansion.key()
        ).orElseThrow().booleanValue();

        assertThat(expansionEnabled && !maintenanceEnabled).isFalse();
    }

    private boolean tryUpdate(
            AppParameterKey key,
            boolean value,
            long expectedVersion,
            String actor
    ) {
        try {
            tx.executeWithoutResult(status -> service.updateBoolean(
                    key,
                    value,
                    expectedVersion,
                    actor
            ));
            return true;
        } catch (IllegalArgumentException expected) {
            return false;
        }
    }

    private List<String> keys() {
        return Arrays.stream(AppParameterKey.values())
                .map(AppParameterKey::key)
                .sorted()
                .toList();
    }
}
