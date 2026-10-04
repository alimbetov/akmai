package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import org.junit.jupiter.api.Test;

class PublishedContextRevalidatorTest {

    @Test
    void dropsGenerationThatIsNoLongerPublishedBeforeAnswerUse() {
        PublishedSearchProjectionReader reader =
                mock(PublishedSearchProjectionReader.class);
        PublishedContextRevalidator revalidator =
                new PublishedContextRevalidator(reader);

        RetrievalHit stale = hit(1L, "doc", 1L, "old");
        RetrievalHit current = hit(1L, "doc", 2L, "new");

        when(reader.findPublishedByKeys(
                anyList(),
                eq(Set.of(1L))
        )).thenReturn(List.of(projection(
                1L,
                "doc",
                2L,
                "new"
        )));

        assertThat(revalidator.revalidate(
                List.of(stale, current),
                Set.of(1L)
        )).containsExactly(current);
    }

    @Test
    void rejectsHitOutsideEffectiveAccessLevelSet() {
        PublishedSearchProjectionReader reader =
                mock(PublishedSearchProjectionReader.class);
        PublishedContextRevalidator revalidator =
                new PublishedContextRevalidator(reader);

        RetrievalHit unauthorized = hit(
                2L,
                "doc",
                1L,
                "chunk"
        );

        assertThat(revalidator.revalidate(
                List.of(unauthorized),
                Set.of(1L)
        )).isEmpty();
    }

    private RetrievalHit hit(
            long accessLevel,
            String documentId,
            long generation,
            String chunkId
    ) {
        return new RetrievalHit(
                RetrievalType.VECTOR,
                accessLevel,
                documentId,
                generation,
                chunkId,
                "text",
                Map.of()
        );
    }

    private SearchProjection projection(
            long accessLevel,
            String documentId,
            long generation,
            String chunkId
    ) {
        return new SearchProjection(
                chunkId,
                documentId,
                generation,
                accessLevel,
                null,
                0,
                "text",
                "embedding",
                "en",
                KnowledgeDomain.GENERAL,
                "section",
                List.of(),
                List.of(),
                Map.of(),
                2
        );
    }
}
