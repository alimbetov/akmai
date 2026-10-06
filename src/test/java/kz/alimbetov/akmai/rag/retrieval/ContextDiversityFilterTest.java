package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextDiversityFilterTest {

    @Test
    void capsNonAuthoritativeHitsPerDocumentSection() {
        ContextDiversityFilter subject = new ContextDiversityFilter(
                new RetrievalIntelligenceProperties(true, 2, true)
        );

        List<RetrievalHit> result = subject.apply(List.of(
                hit("doc-1", "c1", "A", 2),
                hit("doc-1", "c2", "A", 2),
                hit("doc-1", "c3", "A", 2),
                hit("doc-1", "c4", "B", 2)
        ));

        assertThat(result).extracting(RetrievalHit::chunkId)
                .containsExactly("c1", "c2", "c4");
    }

    @Test
    void neverDropsExactAuthorityHits() {
        ContextDiversityFilter subject = new ContextDiversityFilter(
                new RetrievalIntelligenceProperties(true, 1, true)
        );

        List<RetrievalHit> result = subject.apply(List.of(
                hit("doc-1", "c1", "A", 2),
                hit("doc-1", "c2", "A", 0),
                hit("doc-1", "c3", "A", 2)
        ));

        assertThat(result).extracting(RetrievalHit::chunkId)
                .containsExactly("c1", "c2");
    }

    @Test
    void disabledFilterPreservesBaselineOrder() {
        ContextDiversityFilter subject = new ContextDiversityFilter(
                RetrievalIntelligenceProperties.defaults()
        );
        List<RetrievalHit> input = List.of(
                hit("doc-1", "c1", "A", 2),
                hit("doc-1", "c2", "A", 2),
                hit("doc-1", "c3", "A", 2)
        );

        assertThat(subject.apply(input)).containsExactlyElementsOf(input);
    }

    private RetrievalHit hit(
            String documentId,
            String chunkId,
            String section,
            int authorityTier
    ) {
        return new RetrievalHit(
                RetrievalType.VECTOR,
                1L,
                documentId,
                1L,
                chunkId,
                "text",
                Map.of(
                        "sectionPath", section,
                        "authorityTier", authorityTier
                )
        );
    }
}
