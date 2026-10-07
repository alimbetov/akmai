package kz.alimbetov.akmai.rag.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
class QueryMemoryAntiPoisoningIntegrationTest {

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
    static SemanticQueryMemoryRepository repository;

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
        repository = new SemanticQueryMemoryRepository(
                jdbc,
                new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)
                ),
                new ObjectMapper()
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM rag_query_memory_observation");
        jdbc.update("DELETE FROM rag_query_memory_cluster");
    }

    @Test
    void sameFingerprintIsAdmittedOnlyOncePerCluster() {
        UUID clusterId = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        Instant first = Instant.parse("2026-10-07T00:00:00Z");

        assertThat(repository.persist(
                cluster(
                        clusterId,
                        1,
                        new float[]{1.0f, 0.0f},
                        first
                ),
                fingerprint,
                "first grounded answer",
                List.of("doc:chunk-1"),
                first,
                128,
                4
        )).isTrue();

        long revisionBeforeDuplicate = repository
                .findClustersByProfile("embedding-v1", 10)
                .getFirst()
                .refreshRevision();

        assertThat(repository.persist(
                cluster(
                        clusterId,
                        2,
                        new float[]{0.5f, 0.5f},
                        first.plusSeconds(1)
                ),
                fingerprint,
                "duplicate attempt",
                List.of("doc:chunk-2"),
                first.plusSeconds(1),
                128,
                4
        )).isFalse();

        var stored = repository.findClustersByProfile("embedding-v1", 10)
                .getFirst();
        assertThat(stored.observationCount()).isEqualTo(1);
        assertThat(stored.centroid()).containsExactly(1.0f, 0.0f);
        assertThat(stored.refreshRevision()).isGreaterThan(revisionBeforeDuplicate);
        assertThat(repository.findObservations(clusterId, 10)).hasSize(1);
    }

    @Test
    void differentFingerprintsCanProvideIndependentSupport() {
        UUID clusterId = UUID.randomUUID();
        Instant first = Instant.parse("2026-10-07T00:00:00Z");
        repository.persist(
                cluster(clusterId, 1, new float[]{1.0f, 0.0f}, first),
                "a".repeat(64),
                "first",
                List.of("doc:c1"),
                first,
                128,
                4
        );

        assertThat(repository.persist(
                cluster(
                        clusterId,
                        2,
                        new float[]{0.75f, 0.25f},
                        first.plusSeconds(1)
                ),
                "b".repeat(64),
                "second",
                List.of("doc:c2"),
                first.plusSeconds(1),
                128,
                4
        )).isTrue();

        assertThat(repository.findClustersByProfile("embedding-v1", 10)
                .getFirst().observationCount()).isEqualTo(2);
        assertThat(repository.findObservations(clusterId, 10)).hasSize(2);
    }

    private SemanticQueryMemoryRepository.StoredCluster cluster(
            UUID id,
            int count,
            float[] centroid,
            Instant updatedAt
    ) {
        return new SemanticQueryMemoryRepository.StoredCluster(
                id,
                "embedding-v1",
                Set.of(1L),
                centroid,
                count,
                updatedAt
        );
    }
}
