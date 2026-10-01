package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeExpansionTest {

    @Mock
    private SearchProjectionRepository repository;

    @Test
    void resolvesSeedCoordinatesFromCanonicalProjection() {
        KnowledgeExpansion expansion = new KnowledgeExpansion(repository);
        RetrievalHit seed = new RetrievalHit(
                RetrievalType.VECTOR,
                "doc",
                "chunk-1",
                "seed",
                Map.of()
        );
        SearchProjection canonical = projection("chunk-1", 1);
        SearchProjection neighbor = projection("chunk-2", 2);

        when(repository.findByChunkIds(List.of("chunk-1")))
                .thenReturn(List.of(canonical));
        when(repository.findAdjacent("doc", 1, 1))
                .thenReturn(List.of(canonical, neighbor));

        List<RetrievalHit> result = expansion.expand(List.of(seed));

        assertThat(result).extracting(RetrievalHit::chunkId)
                .containsExactly("chunk-1", "chunk-2");
        assertThat(result.get(1).metadata())
                .containsEntry("expansion", "neighbor");
    }

    private SearchProjection projection(String chunkId, int index) {
        return new SearchProjection(
                chunkId,
                "doc",
                null,
                index,
                "text-" + index,
                "text-" + index,
                "ru",
                KnowledgeDomain.GENERAL,
                "section",
                List.of(),
                List.of(),
                Map.of(),
                1
        );
    }
}
