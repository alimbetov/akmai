package kz.alimbetov.akmai.knowledge.graph.dream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.config.SemanticMemoryProperties;
import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class SemanticGraphPriorWriterIntegrationTest {

    private static final String POLICY_FINGERPRINT = "a".repeat(64);

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
    static SemanticGraphPriorWriter writer;

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

        DreamRuntimeSwitches switches = mock(DreamRuntimeSwitches.class);
        when(switches.applyEnabled()).thenReturn(true);

        AdaptiveGraphProperties graphProperties = mock(AdaptiveGraphProperties.class);
        AdaptiveGraphProperties.Dream dream = mock(AdaptiveGraphProperties.Dream.class);
        when(graphProperties.graphVersion()).thenReturn(1);
        when(graphProperties.dream()).thenReturn(dream);
        when(dream.transactionTimeout()).thenReturn(Duration.ofSeconds(5));

        writer = new SemanticGraphPriorWriter(
                jdbc,
                new DataSourceTransactionManager(dataSource),
                switches,
                graphProperties,
                new SemanticMemoryProperties()
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_chunk_association");
        jdbc.update("DELETE FROM adaptive_graph_dream_lease");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void validAuthorityWritesBothDirectionsWithoutLearnedEvidence() {
        insertGeneration("doc-a", 1, 1);
        insertGeneration("doc-b", 1, 1);
        insertLease("pod-a", 7);

        SemanticGraphPriorWriter.ApplyResult result = writer.applyCandidate(
                authority("pod-a", 7),
                pair(),
                0.96,
                Instant.now()
        );

        assertThat(result).isEqualTo(SemanticGraphPriorWriter.ApplyResult.APPLIED);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_chunk_association",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_chunk_association
                WHERE band = 'CANDIDATE'
                  AND semantic_similarity = 0.96
                  AND support_count = 0
                  AND context_count = 0
                  AND citation_count = 0
                  AND distinct_query_support = 0
                """,
                Integer.class
        )).isEqualTo(2);
    }

    @Test
    void staleFencingTokenRollsBackWholePair() {
        insertGeneration("doc-a", 1, 1);
        insertGeneration("doc-b", 1, 1);
        insertLease("pod-new", 8);

        assertThatThrownBy(() -> writer.applyCandidate(
                authority("pod-old", 7),
                pair(),
                0.96,
                Instant.now()
        )).isInstanceOf(DreamLeaseManager.LostDreamAuthorityException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_chunk_association",
                Integer.class
        )).isZero();
    }

    @Test
    void expiredLifecycleIsRejectedBeforeGraphMutation() {
        insertGeneration("doc-a", 1, 1);
        insertGeneration("doc-b", 1, 1);
        jdbc.update(
                """
                UPDATE knowledge_document_lifecycle
                SET expires_at = clock_timestamp() - interval '1 second'
                WHERE document_id = 'doc-b'
                """
        );
        insertLease("pod-a", 7);

        assertThatThrownBy(() -> writer.applyCandidate(
                authority("pod-a", 7),
                pair(),
                0.96,
                Instant.now()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-expired");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_chunk_association",
                Integer.class
        )).isZero();
    }

    private DreamLeaseManager.Authority authority(String ownerId, long token) {
        return new DreamLeaseManager.Authority(
                1,
                POLICY_FINGERPRINT,
                ownerId,
                token
        );
    }

    private DreamPair pair() {
        return DreamPair.of(
                new ChunkGraphNode(1, "doc-a", 1, "chunk-a"),
                new ChunkGraphNode(1, "doc-b", 1, "chunk-b")
        );
    }

    private void insertLease(String ownerId, long fencingToken) {
        jdbc.update(
                """
                INSERT INTO adaptive_graph_dream_lease (
                    graph_version,
                    semantic_policy_fingerprint,
                    owner_id,
                    lease_until,
                    fencing_token
                ) VALUES (?, ?, ?, clock_timestamp() + interval '1 minute', ?)
                """,
                1,
                POLICY_FINGERPRINT,
                ownerId,
                fencingToken
        );
    }

    private void insertGeneration(
            String documentId,
            long generation,
            long accessLevel
    ) {
        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id,
                    lifecycle_policy,
                    lifecycle_status,
                    generation,
                    attempt_count,
                    row_version,
                    created_at,
                    updated_at,
                    retention_status,
                    published_generation,
                    next_generation,
                    access_level
                ) VALUES (
                    ?, 'PERMANENT', 'READY',
                    ?, 0, 0,
                    clock_timestamp(), clock_timestamp(), 'ACTIVE',
                    ?, ?, ?
                )
                """,
                documentId,
                generation,
                generation,
                generation + 1,
                accessLevel
        );
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
