package kz.alimbetov.akmai.rag.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kz.alimbetov.akmai.config.SelfOptimizingRagProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalExecutionResult;
import kz.alimbetov.akmai.rag.retrieval.plan.AdaptiveRetrievalPlanner;
import kz.alimbetov.akmai.rag.trace.RagExecutionObservationStore;
import kz.alimbetov.akmai.rag.trace.RagRuntimeAttribution;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RagLearningRecorderFailureTest {

    @Test
    void persistenceFailureNeverFailsPrimaryRequest() {
        RagLearningEventRepository repository = mock(RagLearningEventRepository.class);
        LearningPrivacyFingerprint fingerprint = mock(LearningPrivacyFingerprint.class);
        SelfOptimizingRagProperties properties = mock(SelfOptimizingRagProperties.class);
        AdaptiveRetrievalPlanner planner = mock(AdaptiveRetrievalPlanner.class);
        RagRuntimeAttribution attribution = mock(RagRuntimeAttribution.class);
        RagExecutionObservationStore observationStore = mock(RagExecutionObservationStore.class);

        when(properties.learningEventsEnabled()).thenReturn(true);
        when(properties.executionObservationsEnabled()).thenReturn(false);
        when(fingerprint.fingerprint("sensitive user question"))
                .thenReturn("a".repeat(64));
        when(planner.classifyPrimary(anyList()))
                .thenReturn(AdaptiveRetrievalPlanner.QueryClass.GENERIC);
        when(attribution.snapshot()).thenReturn(snapshot());
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository).save(any(RagLearningEvent.class));

        RagLearningRecorder recorder = new RagLearningRecorder(
                repository,
                fingerprint,
                properties,
                planner,
                attribution,
                observationStore
        );

        assertDoesNotThrow(() -> recorder.record(
                UUID.randomUUID().toString(),
                "sensitive user question",
                List.of(),
                Set.of(1L),
                new RetrievalExecutionResult(List.of(), Map.of(), false, false),
                List.of(),
                null,
                RagLearningEvent.AnswerStatus.GROUNDED,
                RagLearningEvent.GroundingStatus.SUPPORTED,
                100L
        ));

        verify(repository).save(any(RagLearningEvent.class));
    }

    @Test
    void persistedEventUsesFingerprintAndDoesNotCarryRawQuestion() {
        RagLearningEventRepository repository = mock(RagLearningEventRepository.class);
        LearningPrivacyFingerprint fingerprint = mock(LearningPrivacyFingerprint.class);
        SelfOptimizingRagProperties properties = mock(SelfOptimizingRagProperties.class);
        AdaptiveRetrievalPlanner planner = mock(AdaptiveRetrievalPlanner.class);
        RagRuntimeAttribution attribution = mock(RagRuntimeAttribution.class);
        RagExecutionObservationStore observationStore = mock(RagExecutionObservationStore.class);

        when(properties.learningEventsEnabled()).thenReturn(true);
        when(properties.executionObservationsEnabled()).thenReturn(false);
        when(fingerprint.fingerprint("sensitive user question"))
                .thenReturn("b".repeat(64));
        when(planner.classifyPrimary(anyList()))
                .thenReturn(AdaptiveRetrievalPlanner.QueryClass.GENERIC);
        when(attribution.snapshot()).thenReturn(snapshot());

        RagLearningRecorder recorder = new RagLearningRecorder(
                repository,
                fingerprint,
                properties,
                planner,
                attribution,
                observationStore
        );

        recorder.record(
                UUID.randomUUID().toString(),
                "sensitive user question",
                List.of(),
                Set.of(1L),
                new RetrievalExecutionResult(List.of(), Map.of(), false, false),
                List.of(),
                null,
                RagLearningEvent.AnswerStatus.GROUNDED,
                RagLearningEvent.GroundingStatus.SUPPORTED,
                100L
        );

        ArgumentCaptor<RagLearningEvent> event = ArgumentCaptor.forClass(
                RagLearningEvent.class
        );
        verify(repository).save(event.capture());
        assertThat(event.getValue().queryFingerprint()).isEqualTo("b".repeat(64));
        assertThat(event.getValue().trace().toString())
                .doesNotContain("sensitive user question");
    }

    @Test
    void disabledLearningDoesNotTouchPersistentRepository() {
        RagLearningEventRepository repository = mock(RagLearningEventRepository.class);
        LearningPrivacyFingerprint fingerprint = mock(LearningPrivacyFingerprint.class);
        SelfOptimizingRagProperties properties = mock(SelfOptimizingRagProperties.class);
        AdaptiveRetrievalPlanner planner = mock(AdaptiveRetrievalPlanner.class);
        RagRuntimeAttribution attribution = mock(RagRuntimeAttribution.class);
        RagExecutionObservationStore observationStore = mock(RagExecutionObservationStore.class);

        when(properties.learningEventsEnabled()).thenReturn(false);
        when(properties.executionObservationsEnabled()).thenReturn(false);

        RagLearningRecorder recorder = new RagLearningRecorder(
                repository,
                fingerprint,
                properties,
                planner,
                attribution,
                observationStore
        );

        recorder.record(
                UUID.randomUUID().toString(),
                "question",
                List.of(),
                Set.of(1L),
                new RetrievalExecutionResult(List.of(), Map.of(), false, false),
                List.of(),
                null,
                RagLearningEvent.AnswerStatus.UNAVAILABLE,
                RagLearningEvent.GroundingStatus.NOT_EVALUATED,
                5L
        );

        verify(repository, never()).save(any(RagLearningEvent.class));
        verify(fingerprint, never()).fingerprint(any());
    }

    private RagRuntimeAttribution.Snapshot snapshot() {
        return new RagRuntimeAttribution.Snapshot(
                "sha",
                "corpus-v1",
                "embedding-v1",
                "retrieval-v1",
                "learning-v1",
                "grounding-v1",
                "test"
        );
    }
}
