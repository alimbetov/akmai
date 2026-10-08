package kz.alimbetov.akmai.knowledge.graph.dream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.config.SemanticMemoryProperties;
import kz.alimbetov.akmai.knowledge.graph.AdaptiveChunkGraphRepository;
import kz.alimbetov.akmai.knowledge.graph.AssociationBand;
import kz.alimbetov.akmai.knowledge.graph.AssociationEvidence;
import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;
import kz.alimbetov.akmai.knowledge.graph.GraphLifecycleGuard;
import kz.alimbetov.akmai.knowledge.graph.GraphNodeLockManager;
import kz.alimbetov.akmai.knowledge.graph.GraphTransactionExecutor;
import kz.alimbetov.akmai.knowledge.graph.SemanticPairAdmissionRepository;
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
class SemanticGraphPriorWriterIntegrationTest {

    private static final String POLICY = "a".repeat(64);

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
    static AdaptiveChunkGraphRepository onlineRepository;

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
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        GraphNodeLockManager lockManager = new GraphNodeLockManager(jdbc);
        GraphLifecycleGuard lifecycleGuard = new GraphLifecycleGuard(jdbc);

        DreamRuntimeSwitches switches = mock(DreamRuntimeSwitches.class);
        when(switches.applyEnabled()).thenReturn(true);

        AdaptiveGraphProperties graphProperties =
                mock(AdaptiveGraphProperties.class, RETURNS_DEEP_STUBS);
        when(graphProperties.graphVersion()).thenReturn(1);
        when(graphProperties.dream().transactionTimeout())
                .thenReturn(Duration.ofSeconds(5));

        writer = new SemanticGraphPriorWriter(
                jdbc,
                switches,
                graphProperties,
                new SemanticMemoryProperties(),
                lockManager,
                lifecycleGuard,
                new GraphTransactionExecutor(transactionManager),
                new DreamAuthorityGuard(jdbc),
                new SemanticPairAdmissionRepository(jdbc)
        );
        onlineRepository = new AdaptiveChunkGraphRepository(
                jdbc,
                new TransactionTemplate(transactionManager),
                lockManager,
                lifecycleGuard
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_chunk_association");
        jdbc.update("DELETE FROM adaptive_graph_dream_lease");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
        insertGeneration("doc-a");
        insertGeneration("doc-b");
    }

    @Test
    void validAuthorityAppliesBothDirectionsWithoutLearnedEvidence() {
        authorityLease("pod-a", 7, "5 minutes");
        DreamLeaseManager.Authority authority = authority("pod-a", 7);

        SemanticGraphPriorWriter.ApplyResult result = writer.applyCandidate(
                authority,
                pair(),
                0.95,
                Instant.parse("2026-10-08T00:00:00Z")
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
                  AND semantic_similarity = 0.95
                  AND support_count = 0
                  AND context_count = 0
                  AND citation_count = 0
                  AND distinct_query_support = 0
                """,
                Integer.class
        )).isEqualTo(2);
    }

    @Test
    void semanticRefreshPreservesLearnedEvidenceAndBand() {
        DreamPair pair = pair();
        onlineRepository.reinforceSymmetric(
                pair.first(),
                pair.second(),
                AssociationBand.HOT,
                new AssociationEvidence(
                        0.81,
                        1,
                        1,
                        1,
                        23,
                        Instant.parse("2026-10-07T00:00:00Z"),
                        1
                )
        );
        authorityLease("pod-a", 7, "5 minutes");

        SemanticGraphPriorWriter.ApplyResult result = writer.applyCandidate(
                authority("pod-a", 7),
                pair,
                0.96,
                Instant.parse("2026-10-08T00:00:00Z")
        );

        assertThat(result).isEqualTo(SemanticGraphPriorWriter.ApplyResult.REFRESHED);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_chunk_association
                WHERE band = 'HOT'
                  AND weight = 0.81
                  AND semantic_similarity = 0.96
                  AND support_count = 1
                  AND context_count = 1
                  AND citation_count = 1
                  AND distinct_query_support = 1
                """,
                Integer.class
        )).isEqualTo(2);
    }

    @Test
    void staleFencingTokenRejectsApplyAndCommitsNoDirection() {
        authorityLease("pod-new", 8, "5 minutes");
        DreamLeaseManager.Authority stale = authority("pod-old", 7);

        assertThatThrownBy(() -> writer.applyCandidate(
                stale,
                pair(),
                0.95,
                Instant.parse("2026-10-08T00:00:00Z")
        )).isInstanceOf(DreamLeaseManager.LostDreamAuthorityException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_chunk_association",
                Integer.class
        )).isZero();
    }

    @Test
    void expiredLeaseRejectsApplyAndCommitsNoDirection() {
        authorityLease("pod-a", 7, "-1 second");

        assertThatThrownBy(() -> writer.applyCandidate(
                authority("pod-a", 7),
                pair(),
                0.95,
                Instant.parse("2026-10-08T00:00:00Z")
        )).isInstanceOf(DreamLeaseManager.LostDreamAuthorityException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_chunk_association",
                Integer.class
        )).isZero();
    }

    private DreamPair pair() {
        return DreamPair.of(
                new ChunkGraphNode(1, "doc-a", 1, "chunk-a"),
                new ChunkGraphNode(1, "doc-b", 1, "chunk-b")
        );
    }

    private DreamLeaseManager.Authority authority(String owner, long token) {
        return new DreamLeaseManager.Authority(1, POLICY, owner, token);
    }

    private void authorityLease(String owner, long token, String leaseOffset) {
        jdbc.update(
                """
                INSERT INTO adaptive_graph_dream_lease (
                    graph_version,
                    semantic_policy_fingerprint,
                    owner_id,
                    lease_until,
                    fencing_token
                ) VALUES (1, ?, ?, clock_timestamp() + (?::interval), ?)
                """,
                POLICY,
                owner,
                leaseOffset,
                token
        );
    }

    private void insertGeneration(String documentId) {
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
                    ?, 'PERMANENT', 'READY', 1, 0, 0,
                    clock_timestamp(), clock_timestamp(), 'ACTIVE',
                    1, 2, 1
                )
                """,
                documentId
        );
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
