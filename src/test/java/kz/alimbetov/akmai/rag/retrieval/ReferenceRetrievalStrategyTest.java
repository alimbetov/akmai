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

    private static final Set<Long> ACCESS = Set.of(1L);
    private static final long GENERATION = 7L;

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
                "doc",
                GENERATION,
                List.of("seed"),
                ACCESS,
                20
        )).thenReturn(List.of("target"));
        when(projectionRepository.findByDocumentGenerationAndChunkIds(
                "doc",
                GENERATION,
                List.of("target"),
                ACCESS
        )).thenReturn(List.of(target));

        ReferenceRetrievalStrategy subject = new ReferenceRetrievalStrategy(
                projectionRepository,
                referenceGraphRepository,
                RetrievalTestProperties.defaults()
        );

        List<RetrievalHit> hits = subject.retrieve(
                new QueryChunk("q", 0, "q", "q", "q", "ru", List.of()),
                new RetrievalContext(List.of(seed("seed")), ACCESS)
        );

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().chunkId()).isEqualTo("target");
        assertThat(hits.getFirst().text())
                .isEqualTo("Полный канонический текст статьи 48.");
        assertThat(hits.getFirst().metadata())
                .containsEntry("expansion", "reference")
                .containsEntry("authority", "EXACT_REFERENCE")
                .containsEntry("authorityTier", 0)
                .containsEntry("generation", GENERATION);
    }

    @Test
    void batchesMultipleSeedChunksPerGenerationIntoSingleGraphLookup() {
        SearchProjection target = projection(
                "target-batch",
                "Canonical target."
        );
        when(referenceGraphRepository.resolveSameDocumentTargets(
                "doc",
                GENERATION,
                List.of("seed-1", "seed-2"),
                ACCESS,
                20
        )).thenReturn(List.of("target-batch"));
        when(projectionRepository.findByDocumentGenerationAndChunkIds(
                "doc",
                GENERATION,
                List.of("target-batch"),
                ACCESS
        )).thenReturn(List.of(target));

        ReferenceRetrievalStrategy subject = new ReferenceRetrievalStrategy(
                projectionRepository,
                referenceGraphRepository,
                RetrievalTestProperties.defaults()
        );

        List<RetrievalHit> hits = subject.retrieve(
                new QueryChunk("q", 0, "q", "q", "q", "ru", List.of()),
                new RetrievalContext(List.of(
                        seed("seed-1"),
                        new RetrievalHit(
                                RetrievalType.VECTOR,
                                "doc",
                                "seed-2",
                                "seed two",
                                Map.of("generation", GENERATION)
                        )
                ), ACCESS)
        );

        assertThat(hits).extracting(RetrievalHit::chunkId)
                .containsExactly("target-batch");
        verify(referenceGraphRepository).resolveSameDocumentTargets(
                "doc",
                GENERATION,
                List.of("seed-1", "seed-2"),
                ACCESS,
                20
        );
    }

    @Test
    void manySeedsRemainOneLookupAndRespectReferenceLimit() {
        java.util.List<RetrievalHit> seeds =
                java.util.stream.IntStream.range(0, 100)
                        .mapToObj(index -> seed("seed-" + index))
                        .toList();
        java.util.List<String> seedIds = seeds.stream()
                .map(RetrievalHit::chunkId)
                .toList();
        java.util.List<String> targetIds =
                java.util.stream.IntStream.range(0, 20)
                        .mapToObj(index -> "target-" + index)
                        .toList();

        when(referenceGraphRepository.resolveSameDocumentTargets(
                "doc",
                GENERATION,
                seedIds,
                ACCESS,
                20
        )).thenReturn(targetIds);
        when(projectionRepository.findByDocumentGenerationAndChunkIds(
                "doc",
                GENERATION,
                targetIds,
                ACCESS
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
                new RetrievalContext(seeds, ACCESS)
        );

        assertThat(hits).hasSize(20);
        verify(referenceGraphRepository).resolveSameDocumentTargets(
                "doc",
                GENERATION,
                seedIds,
                ACCESS,
                20
        );
    }

    @Test
    void dependencyWithoutGenerationCannotCrossPublicationBoundary() {
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
                        "seed",
                        Map.of()
                )), ACCESS)
        );

        assertThat(hits).isEmpty();
    }

    private RetrievalHit seed(String chunkId) {
        return new RetrievalHit(
                RetrievalType.LEXICAL,
                "doc",
                chunkId,
                "seed text",
                Map.of("generation", GENERATION)
        );
    }

    private SearchProjection projection(String chunkId, String text) {
        return new SearchProjection(
                chunkId,
                "doc",
                GENERATION,
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
