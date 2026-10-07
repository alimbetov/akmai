package kz.alimbetov.akmai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import kz.alimbetov.akmai.rag.retrieval.RetrievalProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalTestProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RetrievalTransactionTimeoutIntegrationTest {

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
    static TransactionTemplate retrievalTransaction;

    @BeforeAll
    static void configure() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        jdbc = new JdbcTemplate(dataSource);
        RetrievalProperties properties = properties(
                Duration.ofSeconds(3),
                Duration.ofSeconds(1)
        );
        retrievalTransaction = new TransactionTemplatesConfiguration()
                .retrievalTransactionTemplate(
                        new DataSourceTransactionManager(dataSource),
                        properties
                );
    }

    @Test
    void blockedPostgresQueryTimesOutAndConnectionRemainsReusable() {
        assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> assertThatThrownBy(() ->
                        retrievalTransaction.executeWithoutResult(status ->
                                jdbc.queryForObject(
                                        "SELECT pg_sleep(5)",
                                        Object.class
                                )
                        )
                ).isInstanceOf(DataAccessException.class)
        );

        assertThat(jdbc.queryForObject("SELECT 1", Integer.class))
                .isEqualTo(1);
    }

    private static RetrievalProperties properties(
            Duration requestTimeout,
            Duration strategyTimeout
    ) {
        RetrievalProperties defaults = RetrievalTestProperties.defaults();
        return new RetrievalProperties(
                defaults.parallelism(),
                defaults.queueCapacity(),
                defaults.vectorTopK(),
                defaults.vectorSimilarityThreshold(),
                defaults.lexicalLimit(),
                defaults.identifierLimit(),
                defaults.referenceLimit(),
                defaults.rrfK(),
                defaults.expansionSeeds(),
                defaults.expansionRadius(),
                defaults.expansionMax(),
                defaults.contextMaxTokens(),
                defaults.contextMaxChunks(),
                defaults.contextMaxChunksPerDocument(),
                defaults.rerankerEnabled(),
                defaults.rerankerCandidates(),
                defaults.rerankerTimeout(),
                defaults.rerankerFusedWeight(),
                requestTimeout,
                strategyTimeout,
                defaults.answerTimeout(),
                defaults.embeddingHttpTimeout(),
                defaults.contextExpansionMaxChunks(),
                defaults.answerReservedTokens()
        );
    }
}
