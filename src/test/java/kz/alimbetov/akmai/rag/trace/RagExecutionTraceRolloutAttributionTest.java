package kz.alimbetov.akmai.rag.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import kz.alimbetov.akmai.rag.learning.RagLearningEvent;
import org.junit.jupiter.api.Test;

class RagExecutionTraceRolloutAttributionTest {

    @Test
    void learningProjectionCarriesEffectiveCanaryAttribution() {
        RagExecutionTrace trace = new RagExecutionTrace(
                "request-1",
                "corpus-v1",
                "embedding-v1",
                "retrieval-v2-canary",
                "learning-v1",
                "grounding-v1",
                "en",
                "GENERIC",
                false,
                false,
                Map.of("VECTOR:SUCCESS", 1),
                Map.of("VECTOR", 1),
                Map.of("VECTOR", 1),
                3,
                1,
                1,
                RagLearningEvent.AnswerStatus.GROUNDED,
                RagLearningEvent.GroundingStatus.SUPPORTED,
                120,
                "retrieval-v2-canary",
                "CANARY"
        );

        assertThat(trace.retrievalPolicyVersion())
                .isEqualTo("retrieval-v2-canary");
        assertThat(trace.rolloutPolicyVersion())
                .isEqualTo("retrieval-v2-canary");
        assertThat(trace.rolloutCohort()).isEqualTo("CANARY");
        assertThat(trace.learningProjection())
                .containsEntry("rolloutPolicyVersion", "retrieval-v2-canary")
                .containsEntry("rolloutCohort", "CANARY");
    }

    @Test
    void compatibilityConstructorDefaultsToBaselineCohort() {
        RagExecutionTrace trace = new RagExecutionTrace(
                "request-1",
                "corpus-v1",
                "embedding-v1",
                "retrieval-v1",
                "learning-v1",
                "grounding-v1",
                "en",
                "GENERIC",
                false,
                false,
                Map.of(),
                Map.of(),
                Map.of(),
                0,
                0,
                0,
                RagLearningEvent.AnswerStatus.INSUFFICIENT,
                RagLearningEvent.GroundingStatus.NOT_EVALUATED,
                10
        );

        assertThat(trace.rolloutPolicyVersion()).isEmpty();
        assertThat(trace.rolloutCohort()).isEqualTo("BASELINE");
    }
}
