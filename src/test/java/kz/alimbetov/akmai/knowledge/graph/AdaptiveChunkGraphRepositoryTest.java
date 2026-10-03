package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class AdaptiveChunkGraphRepositoryTest {

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
    static AdaptiveChunkGraphRepository repository;

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
        repository = new AdaptiveChunkGraphRepository(
                jdbc,
                new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)
                )
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_chunk_association");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void provisionsFixedHashLeavesForEveryAcl() {
        Integer leaves = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM pg_inherits inheritance
                JOIN pg_class parent
                  ON parent.oid = inheritance.inhparent
                JOIN pg_class child
                  ON child.oid = inheritance.inhrelid
                WHERE parent.relname =
                    'knowledge_chunk_association_al_1'
                """,
                Integer.class
        );

        assertThat(leaves).isEqualTo(32);
    }

    @Test
    void storesSymmetricAssociationAndRoutesLookupByAcl() {
        insertGeneration("doc-a", 1, 1);
        insertGeneration("doc-b", 1, 1);

        ChunkGraphNode left = new ChunkGraphNode(
                1,
                "doc-a",
                1,
                "chunk-a"
        );
        ChunkGraphNode right = new ChunkGraphNode(
                1,
                "doc-b",
                1,
                "chunk-b"
        );

        repository.reinforceSymmetric(
                left,
                right,
                AssociationBand.HOT,
                new AssociationEvidence(
                        0.91,
                        1,
                        1,
                        1,
                        1,
                        Instant.now(),
                        1
                )
        );

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_chunk_association",
                Integer.class
        );
        assertThat(rows).isEqualTo(2);

        List<ChunkAssociation> related = repository.findRelated(
                Set.of(1L),
                left,
                Set.of(AssociationBand.HOT),
                0.5,
                10
        );
        assertThat(related).hasSize(1);
        assertThat(related.getFirst().target()).isEqualTo(right);
        assertThat(related.getFirst().citationCount()).isEqualTo(1);

        assertThat(repository.findRelated(
                Set.of(2L),
                left,
                Set.of(AssociationBand.HOT),
                0.5,
                10
        )).isEmpty();
    }

    @Test
    void rejectsCrossAclAssociationInJavaAndDatabase() {
        insertGeneration("doc-a", 1, 1);
        insertGeneration("doc-b", 1, 2);

        ChunkGraphNode left = new ChunkGraphNode(
                1,
                "doc-a",
                1,
                "chunk-a"
        );
        ChunkGraphNode right = new ChunkGraphNode(
                2,
                "doc-b",
                1,
                "chunk-b"
        );

        assertThatThrownBy(() ->
                repository.reinforceSymmetric(
                        left,
                        right,
                        AssociationBand.CANDIDATE,
                        new AssociationEvidence(
                                0.4,
                                1,
                                0,
                                0,
                                1,
                                Instant.now(),
                                1
                        )
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cross-ACL");

        assertThatThrownBy(() ->
                jdbc.update(
                        """
                        INSERT INTO knowledge_chunk_association (
                            access_level,
                            source_document_id,
                            source_generation,
                            source_chunk_id,
                            target_document_id,
                            target_generation,
                            target_chunk_id,
                            band,
                            weight
                        ) VALUES (
                            1,
                            'doc-a',
                            1,
                            'chunk-a',
                            'doc-b',
                            1,
                            'chunk-b',
                            'CANDIDATE',
                            0.4
                        )
                        """
                ))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void queryPlanPrunesToAclAndSingleHashLeaf() {
        insertGeneration("doc-a", 1, 1);
        insertGeneration("doc-b", 1, 1);

        repository.reinforceSymmetric(
                new ChunkGraphNode(1, "doc-a", 1, "chunk-a"),
                new ChunkGraphNode(1, "doc-b", 1, "chunk-b"),
                AssociationBand.HOT,
                new AssociationEvidence(
                        0.9,
                        1,
                        1,
                        0,
                        1,
                        Instant.now(),
                        1
                )
        );

        String plan = String.join(
                "\n",
                jdbc.queryForList(
                        """
                        EXPLAIN (COSTS OFF)
                        SELECT target_chunk_id
                        FROM knowledge_chunk_association
                        WHERE access_level = 1
                          AND source_document_id = 'doc-a'
                          AND source_generation = 1
                          AND source_chunk_id = 'chunk-a'
                          AND band = 'HOT'
                        ORDER BY weight DESC
                        LIMIT 5
                        """,
                        String.class
                )
        );

        assertThat(plan)
                .contains("knowledge_chunk_association_al_1_h_")
                .doesNotContain("knowledge_chunk_association_al_2");
    }

    private void insertGeneration(
            String documentId,
            long generation,
            long accessLevel
    ) {
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id,
                    generation,
                    generation_status,
                    generation_kind,
                    access_level
                ) VALUES (?, ?, 'PUBLISHED', 'INGESTION', ?)
                """,
                documentId,
                generation,
                accessLevel
        );
    }
}
