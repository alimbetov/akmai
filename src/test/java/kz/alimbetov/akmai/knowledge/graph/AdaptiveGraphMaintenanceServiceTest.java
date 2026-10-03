package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
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
class AdaptiveGraphMaintenanceServiceTest {

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
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_chunk_association");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void scoresPairsAndEvictsWeakestOverflowSymmetrically() {
        AdaptiveGraphProperties properties =
                AdaptiveGraphTestProperties.create(
                        new AdaptiveGraphProperties.BandQuotas(1, 1, 1)
                );
        AdaptiveChunkGraphRepository repository =
                new AdaptiveChunkGraphRepository(jdbc, tx);
        AdaptiveGraphMaintenanceService maintenance =
                new AdaptiveGraphMaintenanceService(
                        jdbc,
                        tx,
                        properties,
                        new AdaptiveGraphScoreCalculator(properties)
                );

        insertGeneration("doc-a");
        insertGeneration("doc-b");
        insertGeneration("doc-c");

        ChunkGraphNode a = node("doc-a", "a");
        ChunkGraphNode b = node("doc-b", "b");
        ChunkGraphNode c = node("doc-c", "c");

        reinforce(repository, a, b, 1, 1);
        reinforce(repository, a, c, 2, 0);

        AdaptiveGraphMaintenanceService.MaintenanceBatch result =
                maintenance.maintainBatch();

        assertThat(result.scored()).isEqualTo(2);

        List<ChunkAssociation> fromA = repository.findRelated(
                Set.of(1L),
                a,
                Set.of(
                        AssociationBand.CANDIDATE,
                        AssociationBand.WARM,
                        AssociationBand.HOT
                ),
                0.0,
                10
        );
        assertThat(fromA).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_chunk_association",
                Integer.class
        )).isEqualTo(2);
    }

    @Test
    void marksOldCandidateDecayedAndPurgesExpiredPair() {
        AdaptiveGraphProperties properties =
                AdaptiveGraphTestProperties.create(
                        new AdaptiveGraphProperties.BandQuotas(8, 8, 16)
                );
        AdaptiveChunkGraphRepository repository =
                new AdaptiveChunkGraphRepository(jdbc, tx);
        AdaptiveGraphMaintenanceService maintenance =
                new AdaptiveGraphMaintenanceService(
                        jdbc,
                        tx,
                        properties,
                        new AdaptiveGraphScoreCalculator(properties)
                );

        insertGeneration("doc-a");
        insertGeneration("doc-b");

        ChunkGraphNode a = node("doc-a", "a");
        ChunkGraphNode b = node("doc-b", "b");
        reinforce(repository, a, b, 1, 0);

        jdbc.update(
                """
                UPDATE knowledge_chunk_association
                SET last_reinforced_at =
                    clock_timestamp() - interval '31 days'
                """
        );

        maintenance.maintainBatch();

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_chunk_association
                WHERE band = 'DECAYED'
                """,
                Integer.class
        )).isEqualTo(2);

        jdbc.update(
                """
                UPDATE knowledge_chunk_association
                SET decayed_at =
                    clock_timestamp() - interval '15 days'
                """
        );

        maintenance.maintainBatch();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_chunk_association",
                Integer.class
        )).isZero();
    }

    private void reinforce(
            AdaptiveChunkGraphRepository repository,
            ChunkGraphNode left,
            ChunkGraphNode right,
            int distinctQueries,
            int citations
    ) {
        for (int bucket = 0; bucket < distinctQueries; bucket++) {
            repository.reinforceSymmetric(
                    left,
                    right,
                    AssociationBand.CANDIDATE,
                    new AssociationEvidence(
                            0.0,
                            1,
                            1,
                            bucket < citations ? 1 : 0,
                            bucket,
                            Instant.now(),
                            1
                    )
            );
        }
    }

    private ChunkGraphNode node(String documentId, String chunkId) {
        return new ChunkGraphNode(1, documentId, 1, chunkId);
    }

    private void insertGeneration(String documentId) {
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id,
                    generation,
                    generation_status,
                    generation_kind,
                    access_level
                ) VALUES (?, 1, 'PUBLISHED', 'INGESTION', 1)
                """,
                documentId
        );
    }
}
