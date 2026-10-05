package kz.alimbetov.akmai.rag.retrieval;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.rag.retrieval.plan.AdaptiveRetrievalPlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MeasuredRetrievalCoordinatorTest {

    @Mock
    AdaptiveRetrievalPlanner planner;

    @Mock
    RetrievalObserver observer;

    @Test
    void attributesFusedHitToAllContributingBaseLanes() {
        MeasuredRetrievalCoordinator subject =
                new MeasuredRetrievalCoordinator(planner, observer);
        RetrievalHit hit = new RetrievalHit(
                RetrievalType.VECTOR,
                1L,
                "doc",
                1L,
                "chunk",
                "text",
                Map.of(),
                List.of(
                        new RetrievalEvidence(RetrievalType.VECTOR, 1, 0.9),
                        new RetrievalEvidence(RetrievalType.LEXICAL, 1, 0.8)
                ),
                0.1
        );

        subject.recordStage(
                RetrievalAttributionStage.FUSED,
                List.of(hit)
        );

        verify(observer).attribution(
                RetrievalAttributionStage.FUSED,
                RetrievalType.VECTOR,
                1
        );
        verify(observer).attribution(
                RetrievalAttributionStage.FUSED,
                RetrievalType.LEXICAL,
                1
        );
        verifyNoMoreInteractions(observer);
    }

    @Test
    void graphCandidateIsAttributedOnlyToGraphLane() {
        MeasuredRetrievalCoordinator subject =
                new MeasuredRetrievalCoordinator(planner, observer);
        RetrievalHit hit = new RetrievalHit(
                RetrievalType.GRAPH,
                1L,
                "doc",
                1L,
                "graph-chunk",
                "text",
                Map.of(),
                List.of(new RetrievalEvidence(
                        RetrievalType.VECTOR,
                        1,
                        0.9
                )),
                0.1
        );

        subject.recordStage(
                RetrievalAttributionStage.SELECTED,
                List.of(hit)
        );

        verify(observer).attribution(
                RetrievalAttributionStage.SELECTED,
                RetrievalType.GRAPH,
                1
        );
        verifyNoMoreInteractions(observer);
    }
}
