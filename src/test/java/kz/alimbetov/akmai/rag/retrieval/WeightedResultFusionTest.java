package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import org.junit.jupiter.api.Test;

class WeightedResultFusionTest {

    @Test
    void conceptWeightCanPromoteConceptEvidenceWithinSameAuthorityTier() {
        PublishedSearchProjectionReader repository =
                mock(PublishedSearchProjectionReader.class);
        when(repository.findPublishedByKeys(anyList(), anySet()))
                .thenAnswer(invocation -> {
                    List<PublishedSearchProjectionReader.ProjectionKey> keys =
                            invocation.getArgument(0);
                    return keys.stream()
                            .map(this::projection)
                            .toList();
                });
        RetrievalFusionProperties weights =
                new RetrievalFusionProperties(
                        true,
                        1.0,
                        1.0,
                        1.0,
                        2.0,
                        1.0,
                        1.0
                );
        ResultFusion subject = new ResultFusion(
                RetrievalTestProperties.defaults(),
                repository,
                weights
        );

        List<RetrievalHit> fused = subject.fuse(
                List.of(
                        hit(RetrievalType.VECTOR, "vector"),
                        hit(RetrievalType.CONCEPT, "concept")
                ),
                Set.of(1L)
        );

        assertThat(fused.getFirst().chunkId()).isEqualTo("concept");
        assertThat(fused.getFirst().fusedScore())
                .isGreaterThan(fused.get(1).fusedScore());
        assertThat(fused.getFirst().metadata())
                .containsEntry("rrfWeighted", true);
    }

    private RetrievalHit hit(RetrievalType type, String chunkId) {
        return new RetrievalHit(
                type,
                1L,
                "doc",
                1L,
                chunkId,
                chunkId,
                Map.of("queryChunkId", "q1")
        );
    }

    private SearchProjection projection(
            PublishedSearchProjectionReader.ProjectionKey key
    ) {
        return new SearchProjection(
                key.chunkId(),
                key.documentId(),
                key.generation(),
                key.accessLevel(),
                null,
                0,
                key.chunkId(),
                key.chunkId(),
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
