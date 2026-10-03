package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.junit.jupiter.api.Test;

class AdaptiveGraphOnlineExpansionTest {

    @Test
    void appendsRevalidatedGraphCandidateAfterExistingRetrieval() {
        PublishedSearchProjectionReader projectionReader =
                mock(PublishedSearchProjectionReader.class);
        AkmaiMetrics metrics = mock(AkmaiMetrics.class);
        AdaptiveGraphOnlineExpansion expansion =
                new AdaptiveGraphOnlineExpansion(
                        properties(true),
                        projectionReader,
                        metrics
                );

        RetrievalHit existing = hit("seed-doc", "seed", 0.9);
        ChunkGraphNode target =
                new ChunkGraphNode(1, "target-doc", 3, "target");
        AdaptiveGraphShadowExpansion.ShadowCandidate candidate =
                new AdaptiveGraphShadowExpansion.ShadowCandidate(
                        target,
                        0.72,
                        AssociationBand.HOT,
                        2
                );
        AdaptiveGraphShadowExpansion.ShadowExpansionReport report =
                new AdaptiveGraphShadowExpansion.ShadowExpansionReport(
                        1,
                        1,
                        0,
                        0,
                        0,
                        0,
                        List.of(candidate),
                        false
                );

        List<PublishedSearchProjectionReader.ProjectionKey> keys =
                List.of(new PublishedSearchProjectionReader.ProjectionKey(
                        1,
                        "target-doc",
                        3,
                        "target"
                ));
        when(projectionReader.findPublishedByKeys(
                keys,
                Set.of(1L)
        )).thenReturn(List.of(projection()));

        List<RetrievalHit> result = expansion.expand(
                List.of(existing),
                report,
                Set.of(1L)
        );

        assertThat(result).hasSize(2);
        assertThat(result.getFirst()).isSameAs(existing);

        RetrievalHit graph = result.get(1);
        assertThat(graph.type()).isEqualTo(RetrievalType.GRAPH);
        assertThat(graph.accessLevel()).isEqualTo(1);
        assertThat(graph.generation()).isEqualTo(3);
        assertThat(graph.documentId()).isEqualTo("target-doc");
        assertThat(graph.chunkId()).isEqualTo("target");
        assertThat(graph.metadata())
                .containsEntry("expansion", "adaptive_graph")
                .containsEntry("authorityTier", 4)
                .containsEntry("adaptiveGraphBand", "HOT")
                .containsEntry("adaptiveGraphContributingEdges", 2)
                .containsEntry("adaptiveGraphVersion", 1);
        assertThat(graph.fusedScore()).isEqualTo(0.72);

        verify(projectionReader).findPublishedByKeys(
                keys,
                Set.of(1L)
        );
        verify(metrics).adaptiveGraphExpansion("online_added", 1);
    }

    @Test
    void rejectsCandidateThatIsNoLongerPublishedAtAdmissionTime() {
        PublishedSearchProjectionReader projectionReader =
                mock(PublishedSearchProjectionReader.class);
        AkmaiMetrics metrics = mock(AkmaiMetrics.class);
        AdaptiveGraphOnlineExpansion expansion =
                new AdaptiveGraphOnlineExpansion(
                        properties(true),
                        projectionReader,
                        metrics
                );

        RetrievalHit existing = hit("seed-doc", "seed", 0.9);
        AdaptiveGraphShadowExpansion.ShadowExpansionReport report =
                report(new ChunkGraphNode(1, "target-doc", 3, "target"));

        when(projectionReader.findPublishedByKeys(
                List.of(new PublishedSearchProjectionReader.ProjectionKey(
                        1,
                        "target-doc",
                        3,
                        "target"
                )),
                Set.of(1L)
        )).thenReturn(List.of());

        List<RetrievalHit> result = expansion.expand(
                List.of(existing),
                report,
                Set.of(1L)
        );

        assertThat(result).containsExactly(existing);
        verify(metrics).adaptiveGraphExpansion(
                "online_lifecycle_rejected",
                1
        );
    }

    @Test
    void disabledOnlineExpansionDoesNotReadProjection() {
        PublishedSearchProjectionReader projectionReader =
                mock(PublishedSearchProjectionReader.class);
        AdaptiveGraphOnlineExpansion expansion =
                new AdaptiveGraphOnlineExpansion(
                        properties(false),
                        projectionReader,
                        mock(AkmaiMetrics.class)
                );

        RetrievalHit existing = hit("seed-doc", "seed", 0.9);
        List<RetrievalHit> result = expansion.expand(
                List.of(existing),
                report(new ChunkGraphNode(1, "target-doc", 3, "target")),
                Set.of(1L)
        );

        assertThat(result).containsExactly(existing);
        verifyNoInteractions(projectionReader);
    }

    private AdaptiveGraphShadowExpansion.ShadowExpansionReport report(
            ChunkGraphNode node
    ) {
        return new AdaptiveGraphShadowExpansion.ShadowExpansionReport(
                1,
                1,
                0,
                0,
                0,
                0,
                List.of(new AdaptiveGraphShadowExpansion.ShadowCandidate(
                        node,
                        0.72,
                        AssociationBand.HOT,
                        2
                )),
                false
        );
    }

    private RetrievalHit hit(
            String documentId,
            String chunkId,
            double score
    ) {
        return new RetrievalHit(
                RetrievalType.VECTOR,
                1,
                documentId,
                3,
                chunkId,
                "text",
                Map.of("authorityTier", 2),
                List.of(),
                score
        );
    }

    private SearchProjection projection() {
        return new SearchProjection(
                "target",
                "target-doc",
                3,
                1,
                null,
                7,
                "graph target text",
                "graph target text",
                "en",
                KnowledgeDomain.GENERAL,
                "section",
                List.of(),
                List.of(),
                Map.of("source", "target.md"),
                1
        );
    }

    private AdaptiveGraphProperties properties(boolean enabled) {
        AdaptiveGraphProperties base =
                AdaptiveGraphTestProperties.create(
                        new AdaptiveGraphProperties.BandQuotas(
                                8,
                                8,
                                16
                        )
                );
        return new AdaptiveGraphProperties(
                base.learningEnabled(),
                base.maintenanceEnabled(),
                base.shadowExpansionEnabled(),
                enabled,
                base.graphVersion(),
                base.learning(),
                base.shadowExpansion(),
                base.scoring(),
                base.maintenance(),
                base.quotas(),
                base.storage()
        );
    }
}
