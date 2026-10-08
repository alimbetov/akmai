package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.junit.jupiter.api.Test;

class AdaptiveGraphOnlineExpansionFailureModelTest {

    @Test
    void projectionFailureFallsBackToExistingCandidates() {
        PublishedSearchProjectionReader reader =
                mock(PublishedSearchProjectionReader.class);
        AkmaiMetrics metrics = mock(AkmaiMetrics.class);
        AdaptiveGraphOnlineExpansion expansion =
                new AdaptiveGraphOnlineExpansion(
                        properties(true),
                        reader,
                        metrics
                );
        RetrievalHit existing = new RetrievalHit(
                RetrievalType.VECTOR,
                1L,
                "seed-doc",
                3L,
                "seed",
                "seed text",
                Map.of("authorityTier", 2)
        );
        ChunkGraphNode target =
                new ChunkGraphNode(1L, "target-doc", 3L, "target");
        AdaptiveGraphShadowExpansion.ShadowExpansionReport report =
                new AdaptiveGraphShadowExpansion.ShadowExpansionReport(
                        1,
                        1,
                        0,
                        0,
                        0,
                        0,
                        List.of(new AdaptiveGraphShadowExpansion.ShadowCandidate(
                                target,
                                0.72,
                                AssociationBand.HOT,
                                2
                        )),
                        false
                );

        when(reader.findPublishedByKeys(
                List.of(new PublishedSearchProjectionReader.ProjectionKey(
                        1L,
                        "target-doc",
                        3L,
                        "target"
                )),
                Set.of(1L)
        )).thenThrow(new IllegalStateException("projection unavailable"));

        assertThat(expansion.expand(
                List.of(existing),
                report,
                Set.of(1L)
        )).containsExactly(existing);
        verify(metrics).adaptiveGraphExpansion("online_failed", 1);
    }

    @Test
    void failedShadowReportFallsBackWithoutProjectionRead() {
        PublishedSearchProjectionReader reader =
                mock(PublishedSearchProjectionReader.class);
        AdaptiveGraphOnlineExpansion expansion =
                new AdaptiveGraphOnlineExpansion(
                        properties(true),
                        reader,
                        mock(AkmaiMetrics.class)
                );
        RetrievalHit existing = new RetrievalHit(
                RetrievalType.VECTOR,
                1L,
                "seed-doc",
                3L,
                "seed",
                "seed text",
                Map.of("authorityTier", 2)
        );
        AdaptiveGraphShadowExpansion.ShadowExpansionReport report =
                new AdaptiveGraphShadowExpansion.ShadowExpansionReport(
                        1,
                        0,
                        0,
                        0,
                        0,
                        0,
                        List.of(),
                        true
                );

        assertThat(expansion.expand(
                List.of(existing),
                report,
                Set.of(1L)
        )).containsExactly(existing);
        org.mockito.Mockito.verifyNoInteractions(reader);
    }

    private AdaptiveGraphProperties properties(boolean enabled) {
        AdaptiveGraphProperties base =
                AdaptiveGraphTestProperties.create(
                        new AdaptiveGraphProperties.BandQuotas(8, 8, 16)
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
