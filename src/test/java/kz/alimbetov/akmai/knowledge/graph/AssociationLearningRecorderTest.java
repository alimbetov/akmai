package kz.alimbetov.akmai.knowledge.graph;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.runtimeconfig.AppParameterKey;
import kz.alimbetov.akmai.runtimeconfig.AppParameterService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AssociationLearningRecorderTest {

    private static final String SECRET =
            "0123456789abcdef0123456789abcdef";

    @Test
    void disabledLearningDoesNotTouchRepository() {
        AdaptiveChunkGraphRepository repository =
                mock(AdaptiveChunkGraphRepository.class);
        AdaptiveGraphProperties properties = properties(false);

        AssociationLearningRecorder recorder =
                new AssociationLearningRecorder(
                        properties,
                        repository,
                        new PrivacySafeQueryFingerprint(properties),
                        mock(AkmaiMetrics.class)
                );

        recorder.record(
                List.of(query()),
                Set.of(1L),
                List.of(hit("a", "ca", 0.9), hit("b", "cb", 0.8)),
                validation(1, 2)
        );

        verify(repository, never()).reinforceSymmetricBatch(anyList());
    }

    @Test
    void runtimeParameterCanEnableLearningWithoutRestart() {
        AdaptiveChunkGraphRepository repository =
                mock(AdaptiveChunkGraphRepository.class);
        AdaptiveGraphProperties properties =
                properties(false, SECRET);
        AppParameterService appParameters =
                mock(AppParameterService.class);
        when(appParameters.isEnabled(
                AppParameterKey.ADAPTIVE_GRAPH_LEARNING_ENABLED
        )).thenReturn(true);

        AssociationLearningRecorder recorder =
                new AssociationLearningRecorder(
                        properties,
                        repository,
                        new PrivacySafeQueryFingerprint(properties),
                        mock(AkmaiMetrics.class),
                        appParameters
                );

        recorder.record(
                List.of(query()),
                Set.of(1L),
                List.of(hit("a", "ca", 0.9), hit("b", "cb", 0.8)),
                validation(1, 2)
        );

        verify(repository).reinforceSymmetricBatch(anyList());
    }

    @Test
    void recordsCitedPairAsCandidateWithoutChangingRetrieval() {
        AdaptiveChunkGraphRepository repository =
                mock(AdaptiveChunkGraphRepository.class);
        AdaptiveGraphProperties properties = properties(true);
        AkmaiMetrics metrics = mock(AkmaiMetrics.class);

        AssociationLearningRecorder recorder =
                new AssociationLearningRecorder(
                        properties,
                        repository,
                        new PrivacySafeQueryFingerprint(properties),
                        metrics
                );

        recorder.record(
                List.of(query()),
                Set.of(1L),
                List.of(hit("a", "ca", 0.9), hit("b", "cb", 0.8)),
                validation(1, 2)
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AssociationObservation>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(repository).reinforceSymmetricBatch(captor.capture());

        AssociationObservation observation =
                captor.getValue().getFirst();
        org.assertj.core.api.Assertions.assertThat(observation.band())
                .isEqualTo(AssociationBand.CANDIDATE);
        org.assertj.core.api.Assertions.assertThat(
                observation.evidence().citationDelta()
        ).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(
                observation.evidence().weight()
        ).isZero();
    }

    @Test
    void learnsOnlyCitationAnchoredContextPairs() {
        AdaptiveChunkGraphRepository repository =
                mock(AdaptiveChunkGraphRepository.class);
        AdaptiveGraphProperties properties = properties(true);

        AssociationLearningRecorder recorder =
                new AssociationLearningRecorder(
                        properties,
                        repository,
                        new PrivacySafeQueryFingerprint(properties),
                        mock(AkmaiMetrics.class)
                );

        recorder.record(
                List.of(query()),
                Set.of(1L),
                List.of(
                        hit("a", "ca", 0.9),
                        hit("b", "cb", 0.8),
                        hit("c", "cc", 0.7)
                ),
                validation(1)
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AssociationObservation>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(repository).reinforceSymmetricBatch(captor.capture());

        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .hasSize(2)
                .allSatisfy(observation ->
                        org.assertj.core.api.Assertions.assertThat(
                                Set.of(
                                        observation.left().chunkId(),
                                        observation.right().chunkId()
                                )
                        ).contains("ca")
                );
    }

    @Test
    void graphExpandedContextDoesNotReinforceAdaptiveGraph() {
        AdaptiveChunkGraphRepository repository =
                mock(AdaptiveChunkGraphRepository.class);
        AdaptiveGraphProperties properties = properties(true);

        AssociationLearningRecorder recorder =
                new AssociationLearningRecorder(
                        properties,
                        repository,
                        new PrivacySafeQueryFingerprint(properties),
                        mock(AkmaiMetrics.class)
                );

        RetrievalHit graph = new RetrievalHit(
                RetrievalType.GRAPH,
                1,
                "graph-doc",
                1,
                "graph-chunk",
                "graph text",
                Map.of("expansion", "adaptive_graph"),
                List.of(),
                0.9
        );

        recorder.record(
                List.of(query()),
                Set.of(1L),
                List.of(hit("base", "base-chunk", 0.8), graph),
                validation(1, 2)
        );

        verify(repository, never()).reinforceSymmetricBatch(anyList());
    }

    @Test
    void doesNotCreateCrossAclPair() {
        AdaptiveChunkGraphRepository repository =
                mock(AdaptiveChunkGraphRepository.class);
        AdaptiveGraphProperties properties = properties(true);

        AssociationLearningRecorder recorder =
                new AssociationLearningRecorder(
                        properties,
                        repository,
                        new PrivacySafeQueryFingerprint(properties),
                        mock(AkmaiMetrics.class)
                );

        recorder.record(
                List.of(query()),
                Set.of(1L, 2L),
                List.of(
                        hit("a", "ca", 0.9, 1),
                        hit("b", "cb", 0.8, 2)
                ),
                validation(1, 2)
        );

        verify(repository, never()).reinforceSymmetricBatch(anyList());
    }

    private AdaptiveGraphProperties properties(boolean enabled) {
        return properties(
                enabled,
                enabled ? SECRET : ""
        );
    }

    private AdaptiveGraphProperties properties(
            boolean enabled,
            String fingerprintSecret
    ) {
        return new AdaptiveGraphProperties(
                enabled,
                false,
                false,
                false,
                1,
                new AdaptiveGraphProperties.Learning(
                        8,
                        32,
                        fingerprintSecret
                ),
                AdaptiveGraphTestProperties.create(
                        new AdaptiveGraphProperties.BandQuotas(8, 8, 16)
                ).shadowExpansion(),
                AdaptiveGraphTestProperties.create(
                        new AdaptiveGraphProperties.BandQuotas(8, 8, 16)
                ).scoring(),
                AdaptiveGraphTestProperties.create(
                        new AdaptiveGraphProperties.BandQuotas(8, 8, 16)
                ).maintenance(),
                new AdaptiveGraphProperties.BandQuotas(8, 8, 16),
                new AdaptiveGraphProperties.Storage(32)
        );
    }

    private QueryChunk query() {
        return new QueryChunk(
                "q",
                0,
                "Question",
                "question",
                "question",
                "en",
                List.of()
        );
    }

    private RetrievalHit hit(
            String documentId,
            String chunkId,
            double score
    ) {
        return hit(documentId, chunkId, score, 1);
    }

    private RetrievalHit hit(
            String documentId,
            String chunkId,
            double score,
            long accessLevel
    ) {
        return new RetrievalHit(
                RetrievalType.LEXICAL,
                accessLevel,
                documentId,
                1,
                chunkId,
                "text",
                Map.of("source", documentId, "language", "en"),
                List.of(),
                score
        );
    }

    private CitationValidator.CitationValidation validation(
            int... numbers
    ) {
        List<kz.alimbetov.akmai.rag.retrieval.SourceRef> sources =
                java.util.Arrays.stream(numbers)
                        .mapToObj(number ->
                                new kz.alimbetov.akmai.rag.retrieval.SourceRef(
                                        number,
                                        "doc-" + number,
                                        "chunk-" + number,
                                        "source",
                                        "en",
                                        "section",
                                        "1"
                                )
                        )
                        .toList();
        return new CitationValidator.CitationValidation(
                "answer",
                sources,
                List.of()
        );
    }
}
