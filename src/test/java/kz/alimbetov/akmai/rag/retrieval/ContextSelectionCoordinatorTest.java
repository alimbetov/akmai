package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContextSelectionCoordinatorTest {

    @Test
    void backfillsOnceWhenFinalFenceRejectsSelectedCandidate() {
        PublishedLifecycleEligibility lifecycle = mock(PublishedLifecycleEligibility.class);
        ContextDiversityFilter diversity = mock(ContextDiversityFilter.class);
        ContextBudget budget = mock(ContextBudget.class);
        PublishedContextRevalidator revalidator = mock(PublishedContextRevalidator.class);
        ContextSelectionCoordinator coordinator = new ContextSelectionCoordinator(
                lifecycle,
                diversity,
                budget,
                revalidator
        );

        RetrievalHit a = hit("a");
        RetrievalHit b = hit("b");
        RetrievalHit c = hit("c");
        List<RetrievalHit> pool = List.of(a, b, c);
        Set<Long> accessLevels = Set.of(1L);

        when(lifecycle.filter(pool, accessLevels)).thenReturn(pool);
        when(diversity.apply(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(budget.apply(anyList(), eq("question")))
                .thenReturn(List.of(a, b))
                .thenReturn(List.of(b, c));
        when(revalidator.revalidate(anyList(), eq(accessLevels)))
                .thenReturn(List.of(b))
                .thenReturn(List.of(b, c));

        assertThat(coordinator.select(pool, "question", accessLevels))
                .containsExactly(b, c);
        verify(budget, times(2)).apply(anyList(), eq("question"));
        verify(revalidator, times(2)).revalidate(anyList(), eq(accessLevels));
    }

    @Test
    void neverStartsThirdRoundWhenSecondFenceRejectsBackfill() {
        PublishedLifecycleEligibility lifecycle = mock(PublishedLifecycleEligibility.class);
        ContextDiversityFilter diversity = mock(ContextDiversityFilter.class);
        ContextBudget budget = mock(ContextBudget.class);
        PublishedContextRevalidator revalidator = mock(PublishedContextRevalidator.class);
        ContextSelectionCoordinator coordinator = new ContextSelectionCoordinator(
                lifecycle,
                diversity,
                budget,
                revalidator
        );

        RetrievalHit a = hit("a");
        RetrievalHit b = hit("b");
        RetrievalHit c = hit("c");
        List<RetrievalHit> pool = List.of(a, b, c);
        Set<Long> accessLevels = Set.of(1L);

        when(lifecycle.filter(pool, accessLevels)).thenReturn(pool);
        when(diversity.apply(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(budget.apply(anyList(), eq("question")))
                .thenReturn(List.of(a, b))
                .thenReturn(List.of(b, c));
        when(revalidator.revalidate(anyList(), eq(accessLevels)))
                .thenReturn(List.of(b))
                .thenReturn(List.of(b));

        assertThat(coordinator.select(pool, "question", accessLevels))
                .containsExactly(b);
        verify(budget, times(2)).apply(anyList(), eq("question"));
        verify(revalidator, times(2)).revalidate(anyList(), eq(accessLevels));
    }

    @Test
    void skipsBackfillWhenFirstSelectionSurvivesInOrder() {
        PublishedLifecycleEligibility lifecycle = mock(PublishedLifecycleEligibility.class);
        ContextDiversityFilter diversity = mock(ContextDiversityFilter.class);
        ContextBudget budget = mock(ContextBudget.class);
        PublishedContextRevalidator revalidator = mock(PublishedContextRevalidator.class);
        ContextSelectionCoordinator coordinator = new ContextSelectionCoordinator(
                lifecycle,
                diversity,
                budget,
                revalidator
        );

        RetrievalHit a = hit("a");
        RetrievalHit b = hit("b");
        List<RetrievalHit> pool = List.of(a, b);
        Set<Long> accessLevels = Set.of(1L);

        when(lifecycle.filter(pool, accessLevels)).thenReturn(pool);
        when(diversity.apply(pool)).thenReturn(pool);
        when(budget.apply(pool, "question")).thenReturn(pool);
        when(revalidator.revalidate(pool, accessLevels)).thenReturn(pool);

        assertThat(coordinator.select(pool, "question", accessLevels))
                .containsExactly(a, b);
        verify(budget, times(1)).apply(anyList(), eq("question"));
        verify(revalidator, times(1)).revalidate(anyList(), anySet());
    }

    private RetrievalHit hit(String chunkId) {
        return new RetrievalHit(
                RetrievalType.LEXICAL,
                1L,
                "doc",
                1L,
                chunkId,
                "text " + chunkId,
                Map.of("sectionPath", "section-" + chunkId)
        );
    }
}
