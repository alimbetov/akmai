package kz.alimbetov.akmai.rag.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kz.alimbetov.akmai.rag.policy.RagPolicyPromotionService;
import kz.alimbetov.akmai.rag.policy.RagPolicyRegistryRepository;
import kz.alimbetov.akmai.rag.policy.RagPolicyStatus;
import kz.alimbetov.akmai.rag.policy.RagPolicyType;
import kz.alimbetov.akmai.rag.query.SemanticQueryMemoryNamespace;
import kz.alimbetov.akmai.rag.query.SemanticQueryMemoryRepository;
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
class SelfOptimizingRagPersistenceIntegrationTest {

    private static final SemanticQueryMemoryNamespace QUERY_MEMORY_NAMESPACE =
            new SemanticQueryMemoryNamespace(
                    "embedding-v1",
                    "retrieval-v1",
                    "learning-v1",
                    "grounding-v1"
            );

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
    static ObjectMapper mapper;
    static SemanticQueryMemoryRepository memoryRepository;
    static RagLearningEventRepository eventRepository;
    static RagFeedbackRepository feedbackRepository;
    static RagPolicyRegistryRepository policyRepository;
    static RagPolicyPromotionService promotionService;

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
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        mapper = new ObjectMapper();
        memoryRepository = new SemanticQueryMemoryRepository(jdbc, tx, mapper);
        eventRepository = new RagLearningEventRepository(jdbc, mapper);
        feedbackRepository = new RagFeedbackRepository(jdbc);
        policyRepository = new RagPolicyRegistryRepository(jdbc, tx, mapper);
        promotionService = new RagPolicyPromotionService(policyRepository);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM rag_feedback");
        jdbc.update("DELETE FROM rag_learning_event");
        jdbc.update("DELETE FROM rag_query_memory_observation");
        jdbc.update("DELETE FROM rag_query_memory_cluster");
        jdbc.update("DELETE FROM rag_policy_registry");
    }

    @Test
    void persistentQueryMemorySurvivesRepositoryRoundTrip() {
        UUID clusterId = UUID.randomUUID();
        Instant observedAt = Instant.parse("2026-10-06T10:00:00Z");
        memoryRepository.persist(
                new SemanticQueryMemoryRepository.StoredCluster(
                        clusterId,
                        QUERY_MEMORY_NAMESPACE,
                        Set.of(1L, 7L),
                        new float[]{0.25f, 0.75f},
                        3,
                        observedAt
                ),
                "a".repeat(64),
                "Grounded answer",
                List.of("doc-1:chunk-1"),
                observedAt,
                128,
                4
        );

        var clusters = memoryRepository.findClustersByNamespace(
                QUERY_MEMORY_NAMESPACE,
                10
        );
        var observations = memoryRepository.findObservations(
                clusterId,
                QUERY_MEMORY_NAMESPACE,
                10
        );

        assertThat(clusters).hasSize(1);
        assertThat(clusters.getFirst().refreshRevision()).isPositive();
        assertThat(clusters.getFirst().namespace()).isEqualTo(QUERY_MEMORY_NAMESPACE);
        assertThat(clusters.getFirst().requiredAccessLevels())
                .containsExactlyInAnyOrder(1L, 7L);
        assertThat(clusters.getFirst().centroid())
                .containsExactly(0.25f, 0.75f);
        assertThat(observations).hasSize(1);
        assertThat(observations.getFirst().groundedAnswer())
                .isEqualTo("Grounded answer");
    }

    @Test
    void queryMemoryRevisionSupportsReplicaRefreshAndTtlCleanup() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Instant now = Instant.now();

        persistCluster(firstId, now.minusSeconds(2));
        var first = memoryRepository.findClustersByNamespace(
                        QUERY_MEMORY_NAMESPACE,
                        10
                ).stream()
                .filter(value -> value.clusterId().equals(firstId))
                .findFirst()
                .orElseThrow();

        persistCluster(secondId, now.minusSeconds(1));
        var incremental = memoryRepository.findClustersUpdatedAfter(
                QUERY_MEMORY_NAMESPACE,
                first.cursor(),
                10
        );

        assertThat(incremental)
                .extracting(SemanticQueryMemoryRepository.StoredCluster::clusterId)
                .contains(secondId);
        assertThat(incremental)
                .allMatch(value -> value.refreshRevision() > first.refreshRevision());

        jdbc.update(
                """
                UPDATE rag_query_memory_cluster
                SET updated_at = clock_timestamp() - interval '31 days'
                WHERE cluster_id = ?
                """,
                firstId
        );
        int deleted = memoryRepository.deleteExpired(
                QUERY_MEMORY_NAMESPACE,
                Instant.now().minus(Duration.ofDays(30))
        );

        assertThat(deleted).isEqualTo(1);
        assertThat(memoryRepository.findObservations(
                firstId,
                QUERY_MEMORY_NAMESPACE,
                10
        )).isEmpty();
        assertThat(memoryRepository.findClustersByNamespace(
                QUERY_MEMORY_NAMESPACE,
                10
        )).extracting(SemanticQueryMemoryRepository.StoredCluster::clusterId)
                .containsExactly(secondId);
    }

    @Test
    void learningEventAndFeedbackKeepAclAndIdempotencyContracts() {
        UUID requestId = UUID.randomUUID();
        eventRepository.save(event(requestId, Set.of(7L)));

        assertThat(eventRepository.findAccessLevels(requestId))
                .contains(Set.of(7L));
        assertThat(feedbackRepository.record(
                "feedback-key",
                requestId,
                RagFeedbackReason.GOOD,
                null
        )).isEqualTo(RagFeedbackRepository.Result.CREATED);
        assertThat(feedbackRepository.record(
                "feedback-key",
                requestId,
                RagFeedbackReason.GOOD,
                null
        )).isEqualTo(RagFeedbackRepository.Result.REPLAY);

        assertThatThrownBy(() -> feedbackRepository.record(
                "feedback-key",
                requestId,
                RagFeedbackReason.WRONG_ANSWER,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another feedback payload");
    }

    @Test
    void policyRequiresOfflineShadowAndCanaryEvidenceBeforeApproval() {
        String version = "retrieval-candidate-1";
        policyRepository.registerCandidate(
                RagPolicyType.RETRIEVAL,
                version,
                Map.of("routes", Map.of("GENERIC", List.of("VECTOR", "LEXICAL")))
        );

        assertThatThrownBy(() -> promotionService.makeShadow(
                RagPolicyType.RETRIEVAL,
                version
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("securityPassed");

        policyRepository.attachReports(
                RagPolicyType.RETRIEVAL,
                version,
                quality(false, false),
                Map.of("performancePassed", true)
        );
        promotionService.makeShadow(RagPolicyType.RETRIEVAL, version);
        assertThat(policyRepository.find(RagPolicyType.RETRIEVAL, version))
                .get()
                .extracting(RagPolicyRegistryRepository.PolicyRecord::status)
                .isEqualTo(RagPolicyStatus.SHADOW);

        assertThatThrownBy(() -> promotionService.makeCanary(
                RagPolicyType.RETRIEVAL,
                version
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shadowPassed");

        policyRepository.attachReports(
                RagPolicyType.RETRIEVAL,
                version,
                quality(true, false),
                Map.of("performancePassed", true)
        );
        promotionService.makeCanary(RagPolicyType.RETRIEVAL, version);
        assertThat(policyRepository.find(RagPolicyType.RETRIEVAL, version))
                .get()
                .extracting(RagPolicyRegistryRepository.PolicyRecord::status)
                .isEqualTo(RagPolicyStatus.CANARY);

        assertThatThrownBy(() -> promotionService.approve(
                RagPolicyType.RETRIEVAL,
                version
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("canaryPassed");

        policyRepository.attachReports(
                RagPolicyType.RETRIEVAL,
                version,
                quality(true, true),
                Map.of("performancePassed", true)
        );
        promotionService.approve(RagPolicyType.RETRIEVAL, version);

        assertThat(policyRepository.approved(RagPolicyType.RETRIEVAL))
                .get()
                .extracting(RagPolicyRegistryRepository.PolicyRecord::version)
                .isEqualTo(version);
    }

    private Map<String, Object> quality(
            boolean shadowPassed,
            boolean canaryPassed
    ) {
        return Map.of(
                "securityPassed", true,
                "correctnessPassed", true,
                "qualityPassed", true,
                "shadowPassed", shadowPassed,
                "canaryPassed", canaryPassed
        );
    }

    private void persistCluster(
            UUID clusterId,
            Instant observedAt
    ) {
        memoryRepository.persist(
                new SemanticQueryMemoryRepository.StoredCluster(
                        clusterId,
                        QUERY_MEMORY_NAMESPACE,
                        Set.of(1L),
                        new float[]{0.1f, 0.9f},
                        1,
                        observedAt
                ),
                UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""),
                "Grounded answer " + clusterId,
                List.of("doc:chunk"),
                observedAt,
                128,
                4
        );
    }

    private RagLearningEvent event(UUID requestId, Set<Long> scope) {
        return new RagLearningEvent(
                UUID.randomUUID(),
                requestId,
                "b".repeat(64),
                scope,
                "en",
                "GENERIC",
                "corpus-v1",
                "embedding-v1",
                "retrieval-v1",
                "learning-v1",
                "grounding-v1",
                RagLearningEvent.AnswerStatus.GROUNDED,
                RagLearningEvent.GroundingStatus.SUPPORTED,
                5,
                3,
                1,
                123,
                Map.of("degraded", false),
                Instant.parse("2026-10-06T10:00:00Z")
        );
    }
}
