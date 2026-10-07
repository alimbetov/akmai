package kz.alimbetov.akmai.rag.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
class RagPolicyCanaryGateIntegrationTest {

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
    static RagPolicyRegistryRepository policies;
    static RagPolicyCanaryObservationRepository observations;
    static RagPolicyPromotionService promotion;

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
        policies = new RagPolicyRegistryRepository(
                jdbc,
                new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)
                ),
                new ObjectMapper()
        );
        observations = new RagPolicyCanaryObservationRepository(jdbc);
        CanaryEvaluationProperties properties = new CanaryEvaluationProperties(
                5.0,
                10,
                20,
                3,
                0.03,
                0.01,
                0.01,
                0.05,
                0.20,
                100
        );
        RagPolicyCanaryGateService gate = new RagPolicyCanaryGateService(
                policies,
                observations,
                properties
        );
        ApprovedRetrievalPolicyProvider approved =
                new ApprovedRetrievalPolicyProvider(policies);
        ShadowRetrievalPolicyProvider shadow =
                new ShadowRetrievalPolicyProvider(policies);
        CanaryRetrievalPolicyProvider canary =
                new CanaryRetrievalPolicyProvider(policies);
        promotion = new RagPolicyPromotionService(
                policies,
                approved,
                shadow,
                canary,
                null,
                gate
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM rag_policy_canary_observation");
        jdbc.update("DELETE FROM rag_policy_registry");
    }

    @Test
    void approvalRequiresMeasuredCanaryVersusControlEvidence() {
        String version = "retrieval-canary-1";
        installCanary(version);

        assertThatThrownBy(() -> promotion.approve(
                RagPolicyType.RETRIEVAL,
                version
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("canaryPassed");

        for (int index = 0; index < 20; index++) {
            save(
                    version,
                    CanaryRoutingObservationStore.Cohort.CONTROL,
                    index,
                    100 + index,
                    true
            );
        }
        for (int index = 0; index < 10; index++) {
            save(
                    version,
                    CanaryRoutingObservationStore.Cohort.CANARY,
                    index + 100,
                    105 + index,
                    true
            );
        }

        promotion.approve(RagPolicyType.RETRIEVAL, version);

        var policy = policies.find(RagPolicyType.RETRIEVAL, version)
                .orElseThrow();
        assertThat(policy.status()).isEqualTo(RagPolicyStatus.APPROVED);
        assertThat(policy.qualityReport())
                .containsEntry("canaryPassed", true)
                .containsEntry("canarySamples", 10)
                .containsEntry("canaryControlSamples", 20);
        assertThat((double) policy.qualityReport().get("canaryGroundedRate"))
                .isEqualTo(1.0);
        assertThat((double) policy.qualityReport().get("canaryControlGroundedRate"))
                .isEqualTo(1.0);
    }

    @Test
    void canaryRegressionKeepsApprovalClosed() {
        String version = "retrieval-canary-regression";
        installCanary(version);

        for (int index = 0; index < 20; index++) {
            save(
                    version,
                    CanaryRoutingObservationStore.Cohort.CONTROL,
                    index,
                    100,
                    true
            );
        }
        for (int index = 0; index < 10; index++) {
            save(
                    version,
                    CanaryRoutingObservationStore.Cohort.CANARY,
                    index + 100,
                    110,
                    index < 7
            );
        }

        assertThatThrownBy(() -> promotion.approve(
                RagPolicyType.RETRIEVAL,
                version
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("canaryPassed");

        var policy = policies.find(RagPolicyType.RETRIEVAL, version)
                .orElseThrow();
        assertThat(policy.status()).isEqualTo(RagPolicyStatus.CANARY);
        assertThat(policy.qualityReport())
                .containsEntry("canaryPassed", false)
                .containsEntry("canaryGroundedRegressionPassed", false);
    }

    @Test
    void duplicateRequestCannotReinforceCanaryEvidence() {
        String version = "retrieval-canary-dedup";
        UUID requestId = UUID.randomUUID();
        var observation = new RagPolicyCanaryObservationRepository.Observation(
                version,
                requestId,
                CanaryRoutingObservationStore.Cohort.CANARY,
                "a".repeat(64),
                "GENERIC",
                "GROUNDED",
                "SUPPORTED",
                false,
                false,
                10,
                Instant.now()
        );

        assertThat(observations.save(observation)).isTrue();
        assertThat(observations.save(observation)).isFalse();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM rag_policy_canary_observation
                WHERE policy_version = ?
                """,
                Integer.class,
                version
        )).isEqualTo(1);
    }

    private void installCanary(String version) {
        policies.registerCandidate(
                RagPolicyType.RETRIEVAL,
                version,
                Map.of("routes", Map.of("GENERIC", List.of("VECTOR", "REFERENCE")))
        );
        policies.attachReports(
                RagPolicyType.RETRIEVAL,
                version,
                Map.of(
                        "securityPassed", true,
                        "correctnessPassed", true,
                        "qualityPassed", true,
                        "shadowPassed", true,
                        "canaryPassed", false
                ),
                Map.of("performancePassed", true)
        );
        policies.markShadow(RagPolicyType.RETRIEVAL, version);
        policies.markCanary(RagPolicyType.RETRIEVAL, version);
    }

    private void save(
            String version,
            CanaryRoutingObservationStore.Cohort cohort,
            int index,
            long latencyMs,
            boolean grounded
    ) {
        String source = switch (index % 3) {
            case 0 -> "a".repeat(64);
            case 1 -> "b".repeat(64);
            default -> "c".repeat(64);
        };
        observations.save(new RagPolicyCanaryObservationRepository.Observation(
                version,
                UUID.nameUUIDFromBytes((version + ":" + cohort + ":" + index).getBytes()),
                cohort,
                source,
                index % 2 == 0 ? "GENERIC" : "CONCEPTUAL_FUZZY",
                grounded ? "GROUNDED" : "INSUFFICIENT",
                grounded ? "SUPPORTED" : "NOT_EVALUATED",
                false,
                false,
                latencyMs,
                Instant.now()
        ));
    }
}
