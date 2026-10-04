package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.config.AdaptiveGraphCompetitionProperties;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.junit.jupiter.api.Test;

class AdaptiveGraphCompetitiveAdmissionTest {

    @Test
    void disabledCompetitionPreservesAppendOnlyOrder() {
        AkmaiMetrics metrics = mock(AkmaiMetrics.class);
        AdaptiveGraphCompetitiveAdmission admission =
                new AdaptiveGraphCompetitiveAdmission(
                        properties(false),
                        metrics
                );
        List<RetrievalHit> candidates = List.of(
                base("base-1"),
                base("base-2"),
                graph("graph-1", "HOT", 0.90, 2)
        );

        assertThat(admission.admit(candidates))
                .containsExactlyElementsOf(candidates);
        verifyNoInteractions(metrics);
    }

    @Test
    void promotesAtMostTwoStrongHotCandidatesAfterProtectedPrefix() {
        AkmaiMetrics metrics = mock(AkmaiMetrics.class);
        AdaptiveGraphCompetitiveAdmission admission =
                new AdaptiveGraphCompetitiveAdmission(
                        properties(true),
                        metrics
                );
        List<RetrievalHit> candidates = new ArrayList<>();
        for (int index = 1; index <= 8; index++) {
            candidates.add(base("base-" + index));
        }
        candidates.add(graph("graph-low", "HOT", 0.60, 4));
        candidates.add(graph("graph-warm", "WARM", 0.99, 4));
        candidates.add(graph("graph-2", "HOT", 0.80, 1));
        candidates.add(graph("graph-1", "HOT", 0.90, 2));
        candidates.add(graph("graph-3", "HOT", 0.75, 3));

        List<RetrievalHit> result = admission.admit(candidates);

        assertThat(result.subList(0, 8))
                .extracting(RetrievalHit::chunkId)
                .containsExactly(
                        "base-1",
                        "base-2",
                        "base-3",
                        "base-4",
                        "graph-1",
                        "base-5",
                        "graph-2",
                        "base-6"
                );
        assertThat(result)
                .extracting(RetrievalHit::chunkId)
                .containsSubsequence("base-7", "base-8")
                .containsSubsequence(
                        "graph-low",
                        "graph-warm",
                        "graph-3"
                );
        verify(metrics).adaptiveGraphExpansion(
                "competition_promoted",
                2
        );
    }

    @Test
    void doesNotCompeteWhenAllBaseCandidatesAreProtected() {
        AkmaiMetrics metrics = mock(AkmaiMetrics.class);
        AdaptiveGraphCompetitiveAdmission admission =
                new AdaptiveGraphCompetitiveAdmission(
                        properties(true),
                        metrics
                );
        List<RetrievalHit> candidates = List.of(
                base("base-1"),
                base("base-2"),
                base("base-3"),
                base("base-4"),
                graph("graph-1", "HOT", 0.95, 3)
        );

        assertThat(admission.admit(candidates))
                .containsExactlyElementsOf(candidates);
        verifyNoInteractions(metrics);
    }

    private AdaptiveGraphCompetitionProperties properties(boolean enabled) {
        return new AdaptiveGraphCompetitionProperties(
                enabled,
                2,
                4,
                0.70
        );
    }

    private RetrievalHit base(String chunkId) {
        return new RetrievalHit(
                RetrievalType.VECTOR,
                1,
                "base-doc-" + chunkId,
                1,
                chunkId,
                "base",
                Map.of(),
                List.of(),
                0.1
        );
    }

    private RetrievalHit graph(
            String chunkId,
            String band,
            double score,
            int contributingEdges
    ) {
        return new RetrievalHit(
                RetrievalType.GRAPH,
                1,
                "graph-doc-" + chunkId,
                1,
                chunkId,
                "graph",
                Map.of(
                        "adaptiveGraphBand", band,
                        "adaptiveGraphScore", score,
                        "adaptiveGraphContributingEdges", contributingEdges
                ),
                List.of(),
                score
        );
    }
}
