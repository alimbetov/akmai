package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.config.AdaptiveGraphCompetitionProperties;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.runtimeconfig.AppParameterKey;
import kz.alimbetov.akmai.runtimeconfig.AppParameterService;
import org.junit.jupiter.api.Test;

class AdaptiveGraphRuntimeOuterGateTest {

    @Test
    void staticDisableCannotBeOverriddenForShadowExpansion() {
        AdaptiveGraphProperties properties = mock(AdaptiveGraphProperties.class);
        when(properties.shadowExpansionEnabled()).thenReturn(false);
        when(properties.expansionEnabled()).thenReturn(false);
        AppParameterService appParameters = mock(AppParameterService.class);

        AdaptiveGraphShadowExpansion expansion = new AdaptiveGraphShadowExpansion(
                properties,
                mock(AdaptiveGraphLookupReader.class),
                mock(PublishedSearchProjectionReader.class),
                mock(AkmaiMetrics.class),
                appParameters
        );

        AdaptiveGraphShadowExpansion.ShadowExpansionReport report =
                expansion.observe(List.of(), List.of(), Set.of(1L));

        assertThat(report.candidates()).isEmpty();
        verify(appParameters, never()).isEnabled(
                AppParameterKey.ADAPTIVE_GRAPH_SHADOW_EXPANSION_ENABLED
        );
        verify(appParameters, never()).isEnabled(
                AppParameterKey.ADAPTIVE_GRAPH_EXPANSION_ENABLED
        );
    }

    @Test
    void staticDisableCannotBeOverriddenForOnlineExpansion() {
        AdaptiveGraphProperties properties = mock(AdaptiveGraphProperties.class);
        when(properties.expansionEnabled()).thenReturn(false);
        AppParameterService appParameters = mock(AppParameterService.class);
        AdaptiveGraphOnlineExpansion expansion = new AdaptiveGraphOnlineExpansion(
                properties,
                mock(PublishedSearchProjectionReader.class),
                mock(AkmaiMetrics.class),
                appParameters
        );
        RetrievalHit original = hit("base", RetrievalType.LEXICAL);

        List<RetrievalHit> result = expansion.expand(
                List.of(original),
                AdaptiveGraphShadowExpansion.ShadowExpansionReport.empty(),
                Set.of(1L)
        );

        assertThat(result).containsExactly(original);
        verify(appParameters, never()).isEnabled(
                AppParameterKey.ADAPTIVE_GRAPH_EXPANSION_ENABLED
        );
    }

    @Test
    void staticDisableCannotBeOverriddenForCompetitiveAdmission() {
        AdaptiveGraphCompetitionProperties properties =
                mock(AdaptiveGraphCompetitionProperties.class);
        when(properties.enabled()).thenReturn(false);
        AppParameterService appParameters = mock(AppParameterService.class);
        AdaptiveGraphCompetitiveAdmission admission =
                new AdaptiveGraphCompetitiveAdmission(
                        properties,
                        mock(AkmaiMetrics.class),
                        appParameters
                );
        RetrievalHit original = hit("base", RetrievalType.LEXICAL);

        assertThat(admission.admit(List.of(original)))
                .containsExactly(original);
        verify(appParameters, never()).isEnabled(
                AppParameterKey.ADAPTIVE_GRAPH_COMPETITION_ENABLED
        );
    }

    private RetrievalHit hit(String chunkId, RetrievalType type) {
        return new RetrievalHit(
                type,
                1L,
                "doc-" + chunkId,
                1L,
                chunkId,
                "text",
                Map.of(),
                List.of(),
                0.5
        );
    }
}
