package kz.alimbetov.akmai.knowledge.graph.dream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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
class DreamSemanticRetirementIntegrationTest {

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

    static PGSimpleDataSource dataSource;
    static JdbcTemplate jdbc;
    static DreamCandidateRepository candidates;
    static SemanticGraphPriorWriter writer;

    @BeforeAll
    static void migrate() throws Exception {
        dataSource = new PGSimpleDataSource();
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
        candidates = new DreamCandidateRepository(jdbc);
        writer = writer(true);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_chunk_association");
        jdbc.update("DELETE FROM knowledge_chunk_dream_candidate");
        jdbc.update("DELETE FROM adaptive_graph_dream_lease");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void retirementClearsPriorButPreservesLearnedGraphEvidence() {
        DreamLeaseManager.Authority authority = prepareActivePrior();
        jdbc.update(
                """
                UPDATE knowledge_chunk_association
                SET band = 'HOT',
                    weight = 0.77,
                    support_count = 5,
                    context_count = 3,
                    citation_count = 2,
                    distinct_query_support = 4
                """
        );

        DreamBudget budget = budget(10);
        SemanticGraphPriorWriter.RetirementResult result =
                writer.retireCandidate(
                        authority,
                        pair(),
                        UUID.randomUUID(),
                        "not-in-forward-topk",
                        Instant.now(),
                        budget
                );

        assertThat(result).isEqualTo(
                SemanticGraphPriorWriter.RetirementResult.CANDIDATE_AND_PRIOR
        );
        assertThat(candidateState()).isEqualTo("STALE");
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_chunk_association
                WHERE semantic_similarity IS NULL
                  AND band = 'HOT'
                  AND weight = 0.77
                  AND support_count = 5
                  AND context_count = 3
                  AND citation_count = 2
                  AND distinct_query_support = 4
                """,
                Integer.class
        )).isEqualTo(2);
        assertThat(budget.snapshot().dbRowsTouched()).isEqualTo(3);
    }

    @Test
    void lifecycleInvalidationCanStillRetirePrior() {
        DreamLeaseManager.Authority authority = prepareActivePrior();
        jdbc.update(
                """
                UPDATE knowledge_document_lifecycle
                SET lifecycle_policy = 'TTL',
                    expires_at = clock_timestamp() - interval '1 second'
                WHERE document_id = 'doc-b'
                """
        );
        jdbc.update(
                """
                UPDATE knowledge_document_generation
                SET generation_status = 'RETIRED',
                    retired_at = clock_timestamp(),
                    cleanup_required = TRUE
                WHERE document_id = 'doc-b'
                  AND generation = 1
                """
        );

        SemanticGraphPriorWriter.RetirementResult result =
                writer.retireCandidate(
                        authority,
                        pair(),
                        UUID.randomUUID(),
                        "lifecycle-ineligible",
                        Instant.now(),
                        budget(10)
                );

        assertThat(result).isEqualTo(
                SemanticGraphPriorWriter.RetirementResult.CANDIDATE_AND_PRIOR
        );
        assertThat(candidateState()).isEqualTo("STALE");
        assertThat(activeSemanticRows()).isZero();
    }

    @Test
    void staleFencingTokenRollsBackCandidateAndPriorRetirement() {
        DreamLeaseManager.Authority oldAuthority = prepareActivePrior();
        jdbc.update(
                """
                UPDATE adaptive_graph_dream_lease
                SET owner_id = 'pod-new',
                    fencing_token = 8,
                    lease_until = clock_timestamp() + interval '1 minute'
                WHERE graph_version = 1
                  AND semantic_policy_fingerprint = ?
                """,
                POLICY_FINGERPRINT
        );

        assertThatThrownBy(() -> writer.retireCandidate(
                oldAuthority,
                pair(),
                UUID.randomUUID(),
                "not-in-forward-topk",
                Instant.now(),
                budget(10)
        )).isInstanceOf(DreamLeaseManager.LostDreamAuthorityException.class);

        assertThat(candidateState()).isEqualTo("ACTIVE");
        assertThat(activeSemanticRows()).isEqualTo(2);
    }

    @Test
    void halfPairCorruptionRollsBackCandidateRetirement() {
        DreamLeaseManager.Authority authority = prepareActivePrior();
        DreamPair pair = pair();
        jdbc.update(
                """
                DELETE FROM knowledge_chunk_association
                WHERE source_document_id = ?
                  AND source_generation = ?
                  AND source_chunk_id = ?
                """,
                pair.second().documentId(),
                pair.second().generation(),
                pair.second().chunkId()
        );

        assertThatThrownBy(() -> writer.retireCandidate(
                authority,
                pair,
                UUID.randomUUID(),
                "not-in-forward-topk",
                Instant.now(),
                budget(10)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("symmetric retirement invariant");

        assertThat(candidateState()).isEqualTo("ACTIVE");
        assertThat(activeSemanticRows()).isEqualTo(1);
    }

    @Test
    void shadowModeRetiresCandidateWithoutGraphMutationBudget() {
        insertGeneration("doc-a");
        insertGeneration("doc-b");
        insertLease("pod-a", 7);
        DreamLeaseManager.Authority authority = authority("pod-a", 7);
        observeActive(authority);

        DreamBudget budget = budget(1);
        SemanticGraphPriorWriter shadowWriter = writer(false);
        SemanticGraphPriorWriter.RetirementResult result =
                shadowWriter.retireCandidate(
                        authority,
                        pair(),
                        UUID.randomUUID(),
                        "not-in-forward-topk",
                        Instant.now(),
                        budget
                );

        assertThat(result).isEqualTo(
                SemanticGraphPriorWriter.RetirementResult.CANDIDATE_ONLY
        );
        assertThat(candidateState()).isEqualTo("STALE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_chunk_association",
                Integer.class
        )).isZero();
        assertThat(budget.snapshot().dbRowsTouched()).isEqualTo(1);
    }

    private DreamLeaseManager.Authority prepareActivePrior() {
        insertGeneration("doc-a");
        insertGeneration("doc-b");
        insertLease("pod-a", 7);
        DreamLeaseManager.Authority authority = authority("pod-a", 7);
        observeActive(authority);
        assertThat(writer.applyCandidate(
                authority,
                pair(),
                0.96,
                Instant.now()
        )).isEqualTo(SemanticGraphPriorWriter.ApplyResult.APPLIED);
        return authority;
    }

    private void observeActive(DreamLeaseManager.Authority authority) {
        candidates.observe(
                authority,
                new DreamCandidateRepository.Observation(
                        pair(),
                        1,
                        "dream-v1",
                        POLICY_FINGERPRINT,
                        DreamCandidateRepository.CandidateState.ACTIVE,
                        0.96,
                        0.95,
                        1,
                        1,
                        true,
                        0.97,
                        "profile-v1",
                        UUID.randomUUID(),
                        "integration-test",
                        DreamCandidateRepository.ObservationOutcome.POSITIVE,
                        Instant.now()
                )
        );
    }

    private static SemanticGraphPriorWriter writer(boolean applyEnabled) {
        DreamRuntimeSwitches switches = mock(DreamRuntimeSwitches.class);
        when(switches.applyEnabled()).thenReturn(applyEnabled);

        AdaptiveGraphProperties graphProperties = mock(AdaptiveGraphProperties.class);
        AdaptiveGraphProperties.Dream dream = mock(AdaptiveGraphProperties.Dream.class);
        when(graphProperties.graphVersion()).thenReturn(1);
        when(graphProperties.dream()).thenReturn(dream);
        when(dream.transactionTimeout()).thenReturn(Duration.ofSeconds(5));

        return new SemanticGraphPriorWriter(
                jdbc,
                new DataSourceTransactionManager(dataSource),
                switches,
                graphProperties,
                new SemanticMemoryProperties()
        );
    }

    private void insertLease(String ownerId, long token) {
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
                token
        );
    }

    private void insertGeneration(String documentId) {
        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id, lifecycle_policy, lifecycle_status,
                    generation, attempt_count, row_version,
                    created_at, updated_at, retention_status,
                    published_generation, next_generation, access_level
                ) VALUES (
                    ?, 'PERMANENT', 'READY',
                    1, 0, 0,
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

    private DreamBudget budget(long maxRows) {
        return new DreamBudget(
                10,
                10,
                10,
                maxRows,
                Duration.ofMinutes(1)
        );
    }

    private String candidateState() {
        return jdbc.queryForObject(
                "SELECT state FROM knowledge_chunk_dream_candidate",
                String.class
        );
    }

    private int activeSemanticRows() {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_chunk_association
                WHERE semantic_similarity IS NOT NULL
                """,
                Integer.class
        );
    }
}
