package kz.alimbetov.akmai.knowledge.graph;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.junit.jupiter.api.Test;

class AdaptiveGraphUtilityRecorderTest {

    @Test
    void attributesCitedGraphContextAndRecordsRequestAssist() {
        AkmaiMetrics metrics = mock(AkmaiMetrics.class);
        AdaptiveGraphUtilityRecorder recorder =
                new AdaptiveGraphUtilityRecorder(metrics);
        List<RetrievalHit> context = List.of(
                hit(RetrievalType.VECTOR, "base"),
                hit(RetrievalType.GRAPH, "graph")
        );
        CitationValidator.CitationValidation validation =
                new CitationValidator().validate(
                        "answer [SOURCE 2]",
                        context
                );

        recorder.record(context, validation);

        verify(metrics).adaptiveGraphUtilityRequest("selected");
        verify(metrics).adaptiveGraphExpansion("online_cited", 1);
        verify(metrics).adaptiveGraphUtilityRequest("assisted");
    }

    @Test
    void selectedButUncitedGraphRemainsInAssistRateDenominator() {
        AkmaiMetrics metrics = mock(AkmaiMetrics.class);
        AdaptiveGraphUtilityRecorder recorder =
                new AdaptiveGraphUtilityRecorder(metrics);
        List<RetrievalHit> context = List.of(
                hit(RetrievalType.VECTOR, "base"),
                hit(RetrievalType.GRAPH, "graph")
        );
        CitationValidator.CitationValidation validation =
                new CitationValidator().validate(
                        "answer without citations",
                        context
                );

        recorder.record(context, validation);

        verify(metrics).adaptiveGraphUtilityRequest("selected");
        verify(metrics, never()).adaptiveGraphExpansion(
                "online_cited",
                1
        );
        verify(metrics, never()).adaptiveGraphUtilityRequest("assisted");
    }

    @Test
    void rejectedGroundingKeepsSelectionButNeverRecordsAssist() {
        AkmaiMetrics metrics = mock(AkmaiMetrics.class);
        AdaptiveGraphUtilityRecorder recorder =
                new AdaptiveGraphUtilityRecorder(metrics);
        List<RetrievalHit> context = List.of(
                hit(RetrievalType.VECTOR, "base"),
                hit(RetrievalType.GRAPH, "graph")
        );
        CitationValidator.CitationValidation validation =
                new CitationValidator().validate(
                        "answer [SOURCE 2]",
                        context
                );

        recorder.record(context, validation, false);

        verify(metrics).adaptiveGraphUtilityRequest("selected");
        verify(metrics, never()).adaptiveGraphExpansion(
                "online_cited",
                1
        );
        verify(metrics, never()).adaptiveGraphUtilityRequest("assisted");
    }

    @Test
    void baseOnlyContextDoesNotAffectGraphUtilityMetrics() {
        AkmaiMetrics metrics = mock(AkmaiMetrics.class);
        AdaptiveGraphUtilityRecorder recorder =
                new AdaptiveGraphUtilityRecorder(metrics);
        List<RetrievalHit> context = List.of(
                hit(RetrievalType.VECTOR, "base")
        );
        CitationValidator.CitationValidation validation =
                new CitationValidator().validate(
                        "answer [SOURCE 1]",
                        context
                );

        recorder.record(context, validation);

        verify(metrics, never()).adaptiveGraphUtilityRequest("selected");
        verify(metrics, never()).adaptiveGraphUtilityRequest("assisted");
    }

    private RetrievalHit hit(RetrievalType type, String chunkId) {
        return new RetrievalHit(
                type,
                1,
                "doc-" + chunkId,
                1,
                chunkId,
                "text",
                Map.of("source", "source.md"),
                List.of(),
                0.5
        );
    }
}
