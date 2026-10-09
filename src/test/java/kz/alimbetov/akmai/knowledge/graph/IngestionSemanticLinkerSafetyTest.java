package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.config.SemanticMemoryProperties;
import kz.alimbetov.akmai.knowledge.lifecycle.GenerationIdentity;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository.VectorRow;
import kz.alimbetov.akmai.runtimeconfig.AppParameterKey;
import kz.alimbetov.akmai.runtimeconfig.AppParameterService;
import org.junit.jupiter.api.Test;

class IngestionSemanticLinkerSafetyTest {

    @Test
    void runtimeTrueCannotOverrideStaticDisable() {
        SemanticMemoryProperties properties = enabledProperties(false);
        SemanticNeighborSearchRepository neighbors =
                mock(SemanticNeighborSearchRepository.class);
        SemanticAssociationSeedRepository seeds =
                mock(SemanticAssociationSeedRepository.class);
        AppParameterService appParameters = mock(AppParameterService.class);
        when(appParameters.isEnabledAuthoritative(
                AppParameterKey.SEMANTIC_MEMORY_INGESTION_LINKING_ENABLED
        )).thenReturn(true);

        IngestionSemanticLinker linker = new IngestionSemanticLinker(
                properties,
                mock(AdaptiveGraphProperties.class),
                neighbors,
                seeds,
                appParameters
        );

        IngestionSemanticLinker.LinkingReport report =
                linker.linkPublishedGeneration(
                        new GenerationIdentity("doc-a", 1, 10),
                        List.of(row("chunk-a", "en"))
                );

        assertThat(report.enabled()).isFalse();
        verify(appParameters, never()).isEnabledAuthoritative(
                AppParameterKey.SEMANTIC_MEMORY_INGESTION_LINKING_ENABLED
        );
        verifyNoMutation(neighbors, seeds);
    }

    @Test
    void authoritativeRuntimeFalseDisablesStaticLinking() {
        SemanticMemoryProperties properties = enabledProperties(true);
        SemanticNeighborSearchRepository neighbors =
                mock(SemanticNeighborSearchRepository.class);
        SemanticAssociationSeedRepository seeds =
                mock(SemanticAssociationSeedRepository.class);
        AppParameterService appParameters = mock(AppParameterService.class);
        when(appParameters.isEnabledAuthoritative(
                AppParameterKey.SEMANTIC_MEMORY_INGESTION_LINKING_ENABLED
        )).thenReturn(false);

        IngestionSemanticLinker linker = new IngestionSemanticLinker(
                properties,
                mock(AdaptiveGraphProperties.class),
                neighbors,
                seeds,
                appParameters
        );

        IngestionSemanticLinker.LinkingReport report =
                linker.linkPublishedGeneration(
                        new GenerationIdentity("doc-a", 1, 10),
                        List.of(row("chunk-a", "en"))
                );

        assertThat(report.enabled()).isFalse();
        verify(appParameters).isEnabledAuthoritative(
                AppParameterKey.SEMANTIC_MEMORY_INGESTION_LINKING_ENABLED
        );
        verifyNoMutation(neighbors, seeds);
    }

    private void verifyNoMutation(
            SemanticNeighborSearchRepository neighbors,
            SemanticAssociationSeedRepository seeds
    ) {
        verify(neighbors, never()).search(
                any(float[].class),
                anyString(),
                anyLong(),
                anyInt(),
                anyDouble(),
                anyBoolean()
        );
        verify(seeds, never()).seedSymmetric(
                any(), any(), anyDouble(), anyInt(), anyInt(), any()
        );
    }

    private SemanticMemoryProperties enabledProperties(boolean enabled) {
        SemanticMemoryProperties properties = new SemanticMemoryProperties();
        properties.setIngestionLinkingEnabled(enabled);
        properties.setTopK(4);
        properties.setMaxEdgesPerChunk(2);
        properties.setMinSimilarity(0.90);
        properties.setSameLanguageOnly(true);
        return properties;
    }

    private VectorRow row(String chunkId, String language) {
        return new VectorRow(
                "00000000-0000-0000-0000-000000000001",
                chunkId,
                language,
                "content",
                Map.of("language", language),
                new float[]{0.1f, 0.2f}
        );
    }
}
