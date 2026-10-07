package kz.alimbetov.akmai.rag.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
class RagPolicyRollbackIntegrationTest {

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
    static RagPolicyRegistryRepository repository;
    static ApprovedRetrievalPolicyProvider approvedProvider;
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
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        repository = new RagPolicyRegistryRepository(
                jdbc,
                tx,
                new ObjectMapper()
        );
        approvedProvider = new ApprovedRetrievalPolicyProvider(repository);
        ShadowRetrievalPolicyProvider shadowProvider =
                new ShadowRetrievalPolicyProvider(repository);
        promotion = new RagPolicyPromotionService(
                repository,
                approvedProvider,
                shadowProvider
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM rag_policy_registry");
        approvedProvider.invalidate();
    }

    @Test
    void rollbackRestoresSupersededPolicyAndInvalidatesRuntimeCache() {
        registerAndApprove(
                "retrieval-v1",
                List.of("VECTOR", "REFERENCE")
        );
        assertThat(approvedProvider.approvedVersion())
                .contains("retrieval-v1");

        registerAndApprove(
                "retrieval-v2",
                List.of("VECTOR", "LEXICAL", "REFERENCE")
        );
        assertThat(repository.find(RagPolicyType.RETRIEVAL, "retrieval-v1"))
                .get()
                .extracting(RagPolicyRegistryRepository.PolicyRecord::status)
                .isEqualTo(RagPolicyStatus.SUPERSEDED);
        assertThat(approvedProvider.approvedVersion())
                .contains("retrieval-v2");

        promotion.rollback(RagPolicyType.RETRIEVAL, "retrieval-v1");

        assertThat(repository.find(RagPolicyType.RETRIEVAL, "retrieval-v2"))
                .get()
                .extracting(RagPolicyRegistryRepository.PolicyRecord::status)
                .isEqualTo(RagPolicyStatus.ROLLED_BACK);
        assertThat(repository.find(RagPolicyType.RETRIEVAL, "retrieval-v1"))
                .get()
                .extracting(RagPolicyRegistryRepository.PolicyRecord::status)
                .isEqualTo(RagPolicyStatus.APPROVED);
        assertThat(approvedProvider.approvedVersion())
                .contains("retrieval-v1");
    }

    private void registerAndApprove(String version, List<String> lanes) {
        repository.registerCandidate(
                RagPolicyType.RETRIEVAL,
                version,
                Map.of("routes", Map.of("GENERIC", lanes))
        );
        repository.attachReports(
                RagPolicyType.RETRIEVAL,
                version,
                Map.of(
                        "securityPassed", true,
                        "correctnessPassed", true,
                        "qualityPassed", true,
                        "shadowPassed", true,
                        "canaryPassed", true
                ),
                Map.of("performancePassed", true)
        );
        promotion.makeShadow(RagPolicyType.RETRIEVAL, version);
        promotion.makeCanary(RagPolicyType.RETRIEVAL, version);
        promotion.approve(RagPolicyType.RETRIEVAL, version);
    }
}
