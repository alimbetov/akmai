package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.chunking.TokenEstimator;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import org.junit.jupiter.api.Test;

class ResultFusionContextFailureModelTest {

    private static final Set<Long> ACCESS = Set.of(1L);

    @Test
    void duplicateConflictingHitsCollapseToCanonicalPublishedPayload() {
        PublishedSearchProjectionReader reader =
                mock(PublishedSearchProjectionReader.class);
        when(reader.findPublishedByKeys(anyList(), eq(ACCESS)))
                .thenReturn(List.of(projection("CANONICAL TEXT")));
        ResultFusion fusion = new ResultFusion(
                RetrievalTestProperties.defaults(),
                reader
        );

        RetrievalHit vector = hit(
                RetrievalType.VECTOR,
                "vector stale text",
                Map.of("queryChunkId", "q1", "score", 0.91)
        );
        RetrievalHit lexical = hit(
                RetrievalType.LEXICAL,
                "lexical conflicting text",
                Map.of("queryChunkId", "q1", "score", 0.72)
        );

        List<RetrievalHit> result = fusion.fuse(
                List.of(vector, lexical),
                ACCESS
        );

        assertThat(result).hasSize(1);
        RetrievalHit fused = result.getFirst();
        assertThat(fused.text()).isEqualTo("CANONICAL TEXT");
        assertThat(fused.evidence())
                .extracting(RetrievalEvidence::type)
                .containsExactly(RetrievalType.VECTOR, RetrievalType.LEXICAL);
    }

    @Test
    void contextBudgetExhaustionFailsClosedToEmptySelection() {
        ContextBudget budget = new ContextBudget(
                new TokenEstimator(),
                RetrievalTestProperties.defaults()
        );
        RetrievalHit oversized = hit(
                RetrievalType.VECTOR,
                "word ".repeat(30_000),
                Map.of()
        );

        assertThat(budget.apply(List.of(oversized), "question")).isEmpty();
    }

    @Test
    void finalLifecycleFenceCanRemoveHitAfterProjectionWasStillPublished() {
        PublishedSearchProjectionReader reader =
                mock(PublishedSearchProjectionReader.class);
        PublishedLifecycleEligibility lifecycle =
                mock(PublishedLifecycleEligibility.class);
        PublishedContextRevalidator revalidator =
                new PublishedContextRevalidator(reader, lifecycle);
        RetrievalHit hit = hit(RetrievalType.VECTOR, "text", Map.of());

        when(reader.findPublishedByKeys(anyList(), eq(ACCESS)))
                .thenReturn(List.of(projection("text")));
        when(lifecycle.filter(List.of(hit), ACCESS)).thenReturn(List.of());

        assertThat(revalidator.revalidate(List.of(hit), ACCESS)).isEmpty();
        verify(lifecycle).filter(List.of(hit), ACCESS);
    }

    @Test
    void unpublishedGenerationIsDroppedBeforeFinalLifecycleFence() {
        PublishedSearchProjectionReader reader =
                mock(PublishedSearchProjectionReader.class);
        PublishedLifecycleEligibility lifecycle =
                mock(PublishedLifecycleEligibility.class);
        PublishedContextRevalidator revalidator =
                new PublishedContextRevalidator(reader, lifecycle);
        RetrievalHit stale = hit(RetrievalType.VECTOR, "stale", Map.of());

        when(reader.findPublishedByKeys(anyList(), eq(ACCESS)))
                .thenReturn(List.of());

        assertThat(revalidator.revalidate(List.of(stale), ACCESS)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(lifecycle);
    }

    private RetrievalHit hit(
            RetrievalType type,
            String text,
            Map<String, Object> metadata
    ) {
        return new RetrievalHit(
                type,
                1L,
                "doc",
                3L,
                "chunk",
                text,
                metadata
        );
    }

    private SearchProjection projection(String text) {
        return new SearchProjection(
                "chunk",
                "doc",
                3L,
                1L,
                null,
                0,
                text,
                "embedding",
                "en",
                KnowledgeDomain.GENERAL,
                "section",
                List.of(),
                List.of(),
                Map.of("source", "source.md"),
                2
        );
    }
}
