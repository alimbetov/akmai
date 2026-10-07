package kz.alimbetov.akmai.rag.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RagFeedbackAntiPoisoningIntegrationTest {

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
    static RagLearningEventRepository events;
    static RagFeedbackRepository feedback;

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
        ObjectMapper mapper = new ObjectMapper();
        events = new RagLearningEventRepository(jdbc, mapper);
        feedback = new RagFeedbackRepository(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM rag_feedback");
        jdbc.update("DELETE FROM rag_learning_event");
    }

    @Test
    void differentIdempotencyKeysCannotReinforceOneRequestTwice() {
        UUID requestId = UUID.randomUUID();
        events.save(event(requestId));
        String source = "a".repeat(64);

        assertThat(feedback.record(
                "feedback-1",
                requestId,
                RagFeedbackReason.GOOD,
                null,
                RagFeedbackTrustClass.USER_UNVERIFIED,
                source
        )).isEqualTo(RagFeedbackRepository.Result.CREATED);

        assertThat(feedback.record(
                "feedback-2",
                requestId,
                RagFeedbackReason.GOOD,
                null,
                RagFeedbackTrustClass.USER_UNVERIFIED,
                source
        )).isEqualTo(RagFeedbackRepository.Result.REPLAY);

        assertThatThrownBy(() -> feedback.record(
                "feedback-3",
                requestId,
                RagFeedbackReason.WRONG_ANSWER,
                null,
                RagFeedbackTrustClass.USER_UNVERIFIED,
                source
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already has a different feedback signal");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rag_feedback WHERE request_id = ?",
                Integer.class,
                requestId
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT trust_class FROM rag_feedback WHERE request_id = ?",
                String.class,
                requestId
        )).isEqualTo("USER_UNVERIFIED");
    }

    private RagLearningEvent event(UUID requestId) {
        return new RagLearningEvent(
                UUID.randomUUID(),
                requestId,
                "b".repeat(64),
                Set.of(1L),
                "en",
                "GENERIC",
                "corpus-v1",
                "embedding-v1",
                "retrieval-v1",
                "learning-v1",
                "grounding-v1",
                RagLearningEvent.AnswerStatus.GROUNDED,
                RagLearningEvent.GroundingStatus.SUPPORTED,
                4,
                2,
                1,
                100,
                Map.of(),
                Instant.parse("2026-10-07T00:00:00Z")
        );
    }
}
