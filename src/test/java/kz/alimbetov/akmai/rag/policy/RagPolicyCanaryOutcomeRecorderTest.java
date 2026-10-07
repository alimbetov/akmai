package kz.alimbetov.akmai.rag.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import kz.alimbetov.akmai.rag.learning.LearningSourceFingerprint;
import kz.alimbetov.akmai.rag.learning.RagLearningEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RagPolicyCanaryOutcomeRecorderTest {

    @Test
    void consumesRoutingDecisionExactlyOnce() {
        CanaryRoutingObservationStore store = new CanaryRoutingObservationStore();
        RagPolicyCanaryObservationRepository repository =
                mock(RagPolicyCanaryObservationRepository.class);
        LearningSourceFingerprint source = mock(LearningSourceFingerprint.class);
        when(source.currentOperational()).thenReturn("a".repeat(64));
        when(repository.save(any())).thenReturn(true);
        RagPolicyCanaryOutcomeRecorder recorder = new RagPolicyCanaryOutcomeRecorder(
                store,
                repository,
                source
        );
        store.recordCurrent(new CanaryRoutingObservationStore.Decision(
                "retrieval-v2",
                CanaryRoutingObservationStore.Cohort.CANARY,
                "GENERIC",
                true,
                Instant.now()
        ));
        String requestId = UUID.randomUUID().toString();

        recorder.record(
                requestId,
                null,
                RagLearningEvent.AnswerStatus.GROUNDED,
                RagLearningEvent.GroundingStatus.SUPPORTED,
                123
        );
        recorder.record(
                requestId,
                null,
                RagLearningEvent.AnswerStatus.GROUNDED,
                RagLearningEvent.GroundingStatus.SUPPORTED,
                123
        );

        ArgumentCaptor<RagPolicyCanaryObservationRepository.Observation> captor =
                ArgumentCaptor.forClass(
                        RagPolicyCanaryObservationRepository.Observation.class
                );
        verify(repository, times(1)).save(captor.capture());
        var observation = captor.getValue();
        assertThat(observation.policyVersion()).isEqualTo("retrieval-v2");
        assertThat(observation.requestId()).isEqualTo(UUID.fromString(requestId));
        assertThat(observation.cohort())
                .isEqualTo(CanaryRoutingObservationStore.Cohort.CANARY);
        assertThat(observation.sourceFingerprint()).isEqualTo("a".repeat(64));
        assertThat(observation.answerStatus()).isEqualTo("GROUNDED");
        assertThat(observation.groundingStatus()).isEqualTo("SUPPORTED");
        assertThat(observation.totalLatencyMs()).isEqualTo(123);
        assertThat(store.consumeCurrent()).isEmpty();
    }
}
