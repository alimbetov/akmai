package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeExpansionTest {

    private static final Set<Long> ACCESS = Set.of(1L);

    @Mock
    private PublishedSearchProjectionReader repository;

    @Test
    void resolvesSeedCoordinatesFromCanonicalProjection() {
        KnowledgeExpansion expansion =
                new KnowledgeExpansion(
                        repository,
                        RetrievalTestProperties.defaults()
                );
        RetrievalHit seed = new RetrievalHit(
                RetrievalType.VECTOR,
                "doc",
                "chunk-1",
                "seed",
                Map.of("generation", 1L)
        );
        SearchProjection canonical = projection("chunk-1", 1);
        SearchProjection neighbor = projection("chunk-2", 2);

        when(repository.findByDocumentGenerationAndChunkIds(
                "doc",
                1L,
                List.of("chunk-1"),
                ACCESS
        )).thenReturn(List.of(canonical));
        when(repository.findAdjacent(
                "doc",
                1L,
                1,
                1,
                ACCESS
        )).thenReturn(List.of(canonical, neighbor));

        List<RetrievalHit> result =
                expansion.expand(List.of(seed), ACCESS);

        assertThat(result).extracting(RetrievalHit::chunkId)
                .containsExactly("chunk-1", "chunk-2");
        assertThat(result.get(1).metadata())
                .containsEntry("expansion", "neighbor")
                .containsEntry("generation", 1L);
    }

    @Test
    void expandedNeighborIsInterleavedBeforeContextCanFillWithOriginals() {
        KnowledgeExpansion expansion =
                new KnowledgeExpansion(
                        repository,
                        RetrievalTestProperties.defaults()
                );
        java.util.List<RetrievalHit> ranked =
                java.util.stream.IntStream.range(0, 12)
                        .mapToObj(index -> new RetrievalHit(
                                RetrievalType.VECTOR,
                                "doc",
                                "chunk-" + index,
                                "seed-" + index,
                                Map.of(
                                        "chunkIndex", index,
                                        "generation", 1L
                                )
                        ))
                        .toList();
        SearchProjection seed = projection("chunk-0", 0);
        SearchProjection neighbor = projection("neighbor", 1);

        when(repository.findAdjacent(
                "doc",
                1L,
                0,
                1,
                ACCESS
        )).thenReturn(List.of(seed, neighbor));

        List<RetrievalHit> result = expansion.expand(ranked, ACCESS);

        assertThat(result.get(0).chunkId()).isEqualTo("chunk-0");
        assertThat(result.get(1).chunkId()).isEqualTo("neighbor");
        assertThat(result.subList(0, 12))
                .extracting(RetrievalHit::chunkId)
                .contains("neighbor");
    }

    @Test
    void expandedNeighborPreservesCanonicalSourcePageAndSectionProvenance() {
        KnowledgeExpansion expansion =
                new KnowledgeExpansion(
                        repository,
                        RetrievalTestProperties.defaults()
                );
        RetrievalHit seed = new RetrievalHit(
                RetrievalType.VECTOR,
                "doc",
                "seed",
                "seed",
                Map.of(
                        "chunkIndex", 1,
                        "generation", 1L
                )
        );
        SearchProjection canonicalSeed = projection("seed", 1);
        SearchProjection neighbor = new SearchProjection(
                "neighbor-provenance",
                "doc",
                1L,
                null,
                2,
                "neighbor text",
                "neighbor embedding",
                "ru",
                KnowledgeDomain.LEGAL,
                "Статья 25",
                List.of(),
                List.of(),
                Map.of(
                        "source", "law.md",
                        "pageFrom", 7,
                        "pageTo", 8
                ),
                2
        );
        when(repository.findAdjacent(
                "doc",
                1L,
                1,
                1,
                ACCESS
        )).thenReturn(List.of(canonicalSeed, neighbor));

        RetrievalHit expanded =
                expansion.expand(List.of(seed), ACCESS).get(1);

        assertThat(expanded.metadata())
                .containsEntry("source", "law.md")
                .containsEntry("pageFrom", 7)
                .containsEntry("pageTo", 8)
                .containsEntry("sectionPath", "Статья 25")
                .containsEntry("language", "ru");
    }

    @Test
    void emptyAccessScopeFailsLoudly() {
        KnowledgeExpansion expansion =
                new KnowledgeExpansion(
                        repository,
                        RetrievalTestProperties.defaults()
                );
        RetrievalHit seed = new RetrievalHit(
                RetrievalType.VECTOR,
                "doc",
                "seed",
                "seed",
                Map.of("generation", 1L)
        );

        assertThatThrownBy(() -> expansion.expand(
                List.of(seed),
                Set.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessLevels");
    }

    private SearchProjection projection(String chunkId, int index) {
        return new SearchProjection(
                chunkId,
                "doc",
                1L,
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
