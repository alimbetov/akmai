package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReferenceRetrievalStrategyTest {

    @Mock
    private SearchProjectionRepository projectionRepository;

    @Mock
    private ReferenceGraphRepository referenceGraphRepository;

    @Test
    void resolvesPublishedSameDocumentReferenceToCanonicalTarget() {
        SearchProjection target = projection(
                "target",
                "Полный канонический текст статьи 48."
        );

        when(referenceGraphRepository.resolveSameDocumentTargets(
                "doc", List.of("seed"), Set.of(1L), 20
        )).thenReturn(List.of("target"));
        when(projectionRepository.findByDocumentAndChunkIds(
                "doc", List.of("target"), Set.of(1L)
        )).thenReturn(List.of(target));

        ReferenceRetrievalStrategy subject = new ReferenceRetrievalStrategy(
                projectionRepository,
                referenceGraphRepository,
                RetrievalTestProperties.defaults()
        );

        List<RetrievalHit> hits = subject.retrieve(
                new QueryChunk("q", 0, "q", "q", "q", "ru", List.of()),
                new RetrievalContext(List.of(new RetrievalHit(
                        RetrievalType.LEXICAL,
                        "doc",
                        "seed",
                        "seed text",
                        Map.of()
                )), Set.of(1L))
        );

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().chunkId()).isEqualTo("target");
        assertThat(hits.getFirst().text())
                .isEqualTo("Полный канонический текст статьи 48.");
        assertThat(hits.getFirst().metadata())
                .containsEntry("expansion", "reference")
                .containsEntry("authority", "EXACT_REFERENCE")
                .containsEntry("authorityTier", 0);
    }

    @Test
    void batchesMultipleSeedChunksPerDocumentIntoSingleGraphLookup() {
        SearchProjection target = projection(
                "target-batch",
                "Canonical target."
        );
        when(referenceGraphRepository.resolveSameDocumentTargets(
                "doc", List.of("seed-1", "seed-2"), Set.of(1L), 20
        )).thenReturn(List.of("target-batch"));
        when(projectionRepository.findByDocumentAndChunkIds(
                "doc", List.of("target-batch"), Set.of(1L)
        )).thenReturn(List.of(target));

        ReferenceRetrievalStrategy subject = new ReferenceRetrievalStrategy(
                projectionRepository,
                referenceGraphRepository,
                RetrievalTestProperties.defaults()
        );

        List<RetrievalHit> hits = subject.retrieve(
                new QueryChunk("q", 0, "q", "q", "q", "ru", List.of()),
                new RetrievalContext(List.of(
                        new RetrievalHit(
                                RetrievalType.LEXICAL,
                                "doc",
                                "seed-1",
                                "seed one",
                                Map.of()
                        ),
                        new RetrievalHit(
                                RetrievalType.VECTOR,
                                "doc",
                                "seed-2",
                                "seed two",
                                Map.of()
                        )
                ), Set.of(1L))
        );

        assertThat(hits).extracting(RetrievalHit::chunkId)
                .containsExactly("target-batch");
        verify(referenceGraphRepository).resolveSameDocumentTargets(
                "doc",
                List.of("seed-1", "seed-2"),
                Set.of(1L),
                20
        );
    }

    @Test
    void manySeedsRemainOneLookupAndRespectReferenceLimit() {
        java.util.List<RetrievalHit> seeds =
                java.util.stream.IntStream.range(0, 100)
                        .mapToObj(index -> new RetrievalHit(
                                RetrievalType.LEXICAL,
                                "doc",
                                "seed-" + index,
                                "seed",
                                Map.of()
                        ))
                        .toList();
        java.util.List<String> seedIds = seeds.stream()
                .map(RetrievalHit::chunkId)
                .toList();
        java.util.List<String> targetIds =
                java.util.stream.IntStream.range(0, 20)
                        .mapToObj(index -> "target-" + index)
                        .toList();

        when(referenceGraphRepository.resolveSameDocumentTargets(
                "doc", seedIds, Set.of(1L), 20
        )).thenReturn(targetIds);
        when(projectionRepository.findByDocumentAndChunkIds(
                "doc", targetIds, Set.of(1L)
        )).thenReturn(targetIds.stream()
                .map(id -> projection(id, "canonical " + id))
                .toList());

        ReferenceRetrievalStrategy subject = new ReferenceRetrievalStrategy(
                projectionRepository,
                referenceGraphRepository,
                RetrievalTestProperties.defaults()
        );

        List<RetrievalHit> hits = subject.retrieve(
                new QueryChunk("q", 0, "q", "q", "q", "ru", List.of()),
                new RetrievalContext(seeds, Set.of(1L))
        );

        assertThat(hits).hasSize(20);
        verify(referenceGraphRepository).resolveSameDocumentTargets(
                "doc", seedIds, Set.of(1L), 20
        );
    }

    private SearchProjection projection(String chunkId, String text) {
        return new SearchProjection(
                chunkId,
                "doc",
                7L,
                null,
                1,
                text,
                text,
                "ru",
                KnowledgeDomain.LEGAL,
                "Статья 48",
                List.of(),
                List.of(),
                Map.of("source", "law.md"),
                2
        );
    }
}
