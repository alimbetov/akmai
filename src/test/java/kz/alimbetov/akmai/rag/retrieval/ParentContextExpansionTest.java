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
import kz.alimbetov.akmai.knowledge.chunking.ParentChildProperties;
import kz.alimbetov.akmai.knowledge.model.ChunkRole;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import org.junit.jupiter.api.Test;

class ParentContextExpansionTest {

    @Test
    void replacesSiblingChildHitsWithOnePublishedParent() {
        PublishedSearchProjectionReader repository =
                mock(PublishedSearchProjectionReader.class);
        ParentContextExpansion expansion = new ParentContextExpansion(
                repository,
                new ParentChildProperties(
                        true,
                        250,
                        275,
                        300,
                        true,
                        8
                )
        );

        RetrievalHit first = child("child-1", 0, 0.91);
        RetrievalHit second = child("child-2", 1, 0.84);
        SearchProjection parent = parent();

        when(repository.findPublishedByKeys(anyList(), eq(Set.of(7L))))
                .thenReturn(List.of(parent));

        List<RetrievalHit> result = expansion.expand(
                List.of(first, second),
                Set.of(7L)
        );

        assertThat(result).hasSize(1);
        RetrievalHit expanded = result.getFirst();
        assertThat(expanded.chunkId()).isEqualTo("parent-1");
        assertThat(expanded.text()).isEqualTo("Parent context");
        assertThat(expanded.fusedScore()).isEqualTo(0.91);
        assertThat(expanded.metadata())
                .containsEntry("akmaiParentExpansion", true)
                .containsEntry("akmaiMatchedChildChunkId", "child-1")
                .containsEntry(ChunkRole.METADATA_KEY, "PARENT");
        verify(repository).findPublishedByKeys(anyList(), eq(Set.of(7L)));
    }

    private RetrievalHit child(
            String chunkId,
            int childIndex,
            double score
    ) {
        return new RetrievalHit(
                RetrievalType.VECTOR,
                7L,
                "doc-1",
                3L,
                chunkId,
                "Child text " + childIndex,
                Map.of(
                        ChunkRole.METADATA_KEY,
                        ChunkRole.CHILD.name(),
                        ChunkRole.PARENT_CHUNK_ID_KEY,
                        "parent-1",
                        ChunkRole.CHILD_INDEX_KEY,
                        childIndex
                ),
                List.of(),
                score
        );
    }

    private SearchProjection parent() {
        return new SearchProjection(
                "parent-1",
                "doc-1",
                3L,
                7L,
                null,
                0,
                "Parent context",
                "Parent context",
                "en",
                KnowledgeDomain.GENERAL,
                "Section 1",
                List.of(),
                List.of(),
                Map.of(
                        ChunkRole.METADATA_KEY,
                        ChunkRole.PARENT.name()
                ),
                2
        );
    }
}
