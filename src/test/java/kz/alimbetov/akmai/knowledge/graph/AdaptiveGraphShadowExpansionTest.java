package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
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

class AdaptiveGraphShadowExpansionTest {

    @Test
    void disabledShadowExpansionDoesNotReadGraph() {
        AdaptiveGraphProperties properties = properties(false);
        AdaptiveChunkGraphRepository graph =
                mock(AdaptiveChunkGraphRepository.class);
        AdaptiveGraphShadowExpansion expansion =
                new AdaptiveGraphShadowExpansion(
                        properties,
                        graph,
                        mock(PublishedSearchProjectionReader.class),
                        new AkmaiMetrics(new SimpleMeterRegistry())
                );

        expansion.observe(
                List.of(hit("seed", "s", 0.9)),
                List.of(hit("seed", "s", 0.9)),
                Set.of(1L)
        );

        verify(graph, never()).findRelated(
                anySet(),
                org.mockito.ArgumentMatchers.any(),
                anySet(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void reportsPublishedHotCandidateWithoutMutatingRealCandidates() {
        AdaptiveGraphProperties properties = properties(true);
        AdaptiveChunkGraphRepository graph =
                mock(AdaptiveChunkGraphRepository.class);
        PublishedSearchProjectionReader projections =
                mock(PublishedSearchProjectionReader.class);
        AkmaiMetrics metrics =
                new AkmaiMetrics(new SimpleMeterRegistry());

        RetrievalHit seed = hit("seed", "s", 0.9);
        ChunkGraphNode seedNode =
                new ChunkGraphNode(1, "seed", 1, "s");
        ChunkGraphNode target =
                new ChunkGraphNode(1, "target", 1, "t");

        when(graph.findRelated(
                eq(Set.of(1L)),
                eq(seedNode),
                eq(Set.of(AssociationBand.HOT)),
                eq(0.50),
                eq(4)
        )).thenReturn(List.of(association(
                seedNode,
                target,
                AssociationBand.HOT,
                0.8
        )));
        when(graph.findRelated(
                eq(Set.of(1L)),
                eq(seedNode),
                eq(Set.of(AssociationBand.WARM)),
                eq(0.30),
                eq(2)
        )).thenReturn(List.of());
        when(projections.findByDocumentGenerationAndChunkIds(
                "target",
                1,
                List.of("t"),
                Set.of(1L)
        )).thenReturn(List.of(projection("target", "t")));

        List<RetrievalHit> realCandidates = List.of(seed);
        AdaptiveGraphShadowExpansion expansion =
                new AdaptiveGraphShadowExpansion(
                        properties,
                        graph,
                        projections,
                        metrics
                );

        AdaptiveGraphShadowExpansion.ShadowExpansionReport report =
                expansion.observe(
                        List.of(seed),
                        realCandidates,
                        Set.of(1L)
                );

        assertThat(realCandidates).containsExactly(seed);
        assertThat(report.candidates()).hasSize(1);
        assertThat(report.candidates().getFirst().node()).isEqualTo(target);
        assertThat(report.candidates().getFirst().score())
                .isCloseTo(
                        0.8,
                        org.assertj.core.data.Offset.offset(0.0001)
                );
        assertThat(report.candidates().getFirst().strongestBand())
                .isEqualTo(AssociationBand.HOT);
    }

    @Test
    void rejectsDuplicateAndUnpublishedTarget() {
        AdaptiveGraphProperties properties = properties(true);
        AdaptiveChunkGraphRepository graph =
                mock(AdaptiveChunkGraphRepository.class);
        PublishedSearchProjectionReader projections =
                mock(PublishedSearchProjectionReader.class);

        RetrievalHit seed = hit("seed", "s", 0.9);
        RetrievalHit duplicate = hit("duplicate", "d", 0.7);
        ChunkGraphNode seedNode =
                new ChunkGraphNode(1, "seed", 1, "s");
        ChunkGraphNode duplicateNode =
                new ChunkGraphNode(1, "duplicate", 1, "d");
        ChunkGraphNode staleNode =
                new ChunkGraphNode(1, "stale", 1, "x");

        when(graph.findRelated(
                eq(Set.of(1L)),
                eq(seedNode),
                eq(Set.of(AssociationBand.HOT)),
                eq(0.50),
                eq(4)
        )).thenReturn(List.of(
                association(
                        seedNode,
                        duplicateNode,
                        AssociationBand.HOT,
                        0.9
                ),
                association(
                        seedNode,
                        staleNode,
                        AssociationBand.HOT,
                        0.8
                )
        ));
        when(graph.findRelated(
                eq(Set.of(1L)),
                eq(seedNode),
                eq(Set.of(AssociationBand.WARM)),
                eq(0.30),
                eq(2)
        )).thenReturn(List.of());
        when(projections.findByDocumentGenerationAndChunkIds(
                "stale",
                1,
                List.of("x"),
                Set.of(1L)
        )).thenReturn(List.of());

        AdaptiveGraphShadowExpansion expansion =
                new AdaptiveGraphShadowExpansion(
                        properties,
                        graph,
                        projections,
                        new AkmaiMetrics(new SimpleMeterRegistry())
                );

        AdaptiveGraphShadowExpansion.ShadowExpansionReport report =
                expansion.observe(
                        List.of(seed),
                        List.of(seed, duplicate),
                        Set.of(1L)
                );

        assertThat(report.duplicates()).isEqualTo(1);
        assertThat(report.lifecycleRejected()).isEqualTo(1);
        assertThat(report.candidates()).isEmpty();
    }

    @Test
    void aggregatesIndependentSeedContributionsWithBoundedNoisyOr() {
        AdaptiveGraphProperties properties = properties(true);
        AdaptiveChunkGraphRepository graph =
                mock(AdaptiveChunkGraphRepository.class);
        PublishedSearchProjectionReader projections =
                mock(PublishedSearchProjectionReader.class);

        RetrievalHit first = hit("seed-a", "a", 1.0);
        RetrievalHit second = hit("seed-b", "b", 0.8);
        ChunkGraphNode firstNode =
                new ChunkGraphNode(1, "seed-a", 1, "a");
        ChunkGraphNode secondNode =
                new ChunkGraphNode(1, "seed-b", 1, "b");
        ChunkGraphNode target =
                new ChunkGraphNode(1, "target", 1, "t");

        when(graph.findRelated(
                eq(Set.of(1L)),
                eq(firstNode),
                eq(Set.of(AssociationBand.HOT)),
                eq(0.50),
                eq(4)
        )).thenReturn(List.of(
                association(
                        firstNode,
                        target,
                        AssociationBand.HOT,
                        0.5
                )
        ));
        when(graph.findRelated(
                eq(Set.of(1L)),
                eq(secondNode),
                eq(Set.of(AssociationBand.HOT)),
                eq(0.50),
                eq(4)
        )).thenReturn(List.of(
                association(
                        secondNode,
                        target,
                        AssociationBand.HOT,
                        0.5
                )
        ));
        when(graph.findRelated(
                anySet(),
                org.mockito.ArgumentMatchers.any(),
                eq(Set.of(AssociationBand.WARM)),
                eq(0.30),
                eq(2)
        )).thenReturn(List.of());
        when(projections.findByDocumentGenerationAndChunkIds(
                "target",
                1,
                List.of("t"),
                Set.of(1L)
        )).thenReturn(List.of(projection("target", "t")));

        AdaptiveGraphShadowExpansion expansion =
                new AdaptiveGraphShadowExpansion(
                        properties,
                        graph,
                        projections,
                        new AkmaiMetrics(new SimpleMeterRegistry())
                );

        AdaptiveGraphShadowExpansion.ShadowExpansionReport report =
                expansion.observe(
                        List.of(first, second),
                        List.of(first, second),
                        Set.of(1L)
                );

        assertThat(report.candidates()).hasSize(1);
        assertThat(report.candidates().getFirst().contributingEdges())
                .isEqualTo(2);
        assertThat(report.candidates().getFirst().score())
                .isGreaterThan(0.5)
                .isLessThanOrEqualTo(1.0);
    }

    private AdaptiveGraphProperties properties(boolean enabled) {
        AdaptiveGraphProperties base =
                AdaptiveGraphTestProperties.create(
                        new AdaptiveGraphProperties.BandQuotas(8, 8, 16)
                );
        return new AdaptiveGraphProperties(
                false,
                false,
                enabled,
                false,
                base.graphVersion(),
                base.learning(),
                base.shadowExpansion(),
                base.scoring(),
                base.maintenance(),
                base.quotas(),
                base.storage()
        );
    }

    private RetrievalHit hit(
            String documentId,
            String chunkId,
            double fusedScore
    ) {
        return new RetrievalHit(
                RetrievalType.LEXICAL,
                1,
                documentId,
                1,
                chunkId,
                "text",
                Map.of(
                        "authorityTier", 2,
                        "rerankScore", 1.0
                ),
                List.of(),
                fusedScore
        );
    }

    private ChunkAssociation association(
            ChunkGraphNode source,
            ChunkGraphNode target,
            AssociationBand band,
            double weight
    ) {
        return new ChunkAssociation(
                source,
                target,
                band,
                weight,
                5,
                5,
                2,
                5,
                Instant.parse("2026-10-03T00:00:00Z"),
                1
        );
    }

    private SearchProjection projection(
            String documentId,
            String chunkId
    ) {
        return new SearchProjection(
                chunkId,
                documentId,
                1,
                1,
                null,
                1,
                "text",
                "text",
                "en",
                KnowledgeDomain.GENERAL,
                "section",
                List.of(),
                List.of(),
                Map.of("source", documentId),
                2
        );
    }
}
