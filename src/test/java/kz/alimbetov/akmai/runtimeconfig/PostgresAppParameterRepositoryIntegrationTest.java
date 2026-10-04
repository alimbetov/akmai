package kz.alimbetov.akmai.runtimeconfig;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
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
                .containsExactlyElementsOf(keys());
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

    private List<String> keys() {
        return Arrays.stream(AppParameterKey.values())
                .map(AppParameterKey::key)
                .sorted()
                .toList();
    }
}
