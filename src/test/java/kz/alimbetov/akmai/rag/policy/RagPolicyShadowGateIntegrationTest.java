package kz.alimbetov.akmai.rag.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
class RagPolicyShadowGateIntegrationTest {

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
    static RagPolicyShadowObservationRepository observations;
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
        observations = new RagPolicyShadowObservationRepository(jdbc);
        ShadowEvaluationProperties gateProperties =
                new ShadowEvaluationProperties(
                        10,
                        3,
                        0.99,
                        0.95,
                        0.02,
                        15_000,
                        100
                );
        RagPolicyShadowGateService gate = new RagPolicyShadowGateService(
                policies,
                observations,
                gateProperties
        );
        ApprovedRetrievalPolicyProvider approved =
                new ApprovedRetrievalPolicyProvider(policies);
        ShadowRetrievalPolicyProvider shadow =
                new ShadowRetrievalPolicyProvider(policies);
        promotion = new RagPolicyPromotionService(
                policies,
                approved,
                shadow,
                gate
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM rag_policy_shadow_observation");
        jdbc.update("DELETE FROM rag_policy_registry");
    }

    @Test
    void canaryRequiresMeasuredIndependentShadowEvidence() {
        String version = "retrieval-shadow-1";
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
                        "canaryPassed", false
                ),
                Map.of("performancePassed", true)
        );
        promotion.makeShadow(RagPolicyType.RETRIEVAL, version);

        assertThatThrownBy(() -> promotion.makeCanary(
                RagPolicyType.RETRIEVAL,
                version
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shadowPassed");

        for (int index = 0; index < 10; index++) {
            String source = switch (index % 3) {
                case 0 -> "a".repeat(64);
                case 1 -> "b".repeat(64);
                default -> "c".repeat(64);
            };
            assertThat(observations.save(
                    new RagPolicyShadowObservationRepository.Observation(
                            version,
                            String.format("%064x", index + 1),
                            source,
                            index % 2 == 0 ? "GENERIC" : "CONCEPTUAL_FUZZY",
                            true,
                            RagPolicyShadowObservationRepository.Status.SUCCESS,
                            1,
                            1,
                            1,
                            1,
                            50 + index,
                            Instant.now()
                    )
            )).isTrue();
        }

        promotion.makeCanary(RagPolicyType.RETRIEVAL, version);

        var policy = policies.find(RagPolicyType.RETRIEVAL, version)
                .orElseThrow();
        assertThat(policy.status()).isEqualTo(RagPolicyStatus.CANARY);
        assertThat(policy.qualityReport())
                .containsEntry("shadowPassed", true)
                .containsEntry("shadowObservations", 10);
        assertThat((double) policy.qualityReport().get("shadowChunkRetention"))
                .isEqualTo(1.0);
        assertThat((double) policy.qualityReport().get("shadowDocumentRetention"))
                .isEqualTo(1.0);
    }

    @Test
    void duplicateQueryCannotReinforceShadowGate() {
        String version = "retrieval-shadow-dedup";
        policies.registerCandidate(
                RagPolicyType.RETRIEVAL,
                version,
                Map.of("routes", Map.of("GENERIC", List.of("VECTOR")))
        );
        String query = "d".repeat(64);
        var value = new RagPolicyShadowObservationRepository.Observation(
                version,
                query,
                "a".repeat(64),
                "GENERIC",
                true,
                RagPolicyShadowObservationRepository.Status.SUCCESS,
                1,
                1,
                1,
                1,
                10,
                Instant.now()
        );

        assertThat(observations.save(value)).isTrue();
        assertThat(observations.save(value)).isFalse();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM rag_policy_shadow_observation
                WHERE policy_version = ?
                """,
                Integer.class,
                version
        )).isEqualTo(1);
    }
}
