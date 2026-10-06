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
import org.junit.jupiter.api.Test;

class IngestionSemanticLinkerTest {

    @Test
    void disabledLinkingDoesNotSearchOrSeed() {
        SemanticMemoryProperties properties = new SemanticMemoryProperties();
        AdaptiveGraphProperties graphProperties = mock(AdaptiveGraphProperties.class);
        SemanticNeighborSearchRepository neighbors =
                mock(SemanticNeighborSearchRepository.class);
        SemanticAssociationSeedRepository seeds =
                mock(SemanticAssociationSeedRepository.class);

        IngestionSemanticLinker linker = new IngestionSemanticLinker(
                properties,
                graphProperties,
                neighbors,
                seeds
        );

        IngestionSemanticLinker.LinkingReport report =
                linker.linkPublishedGeneration(
                        new GenerationIdentity("doc-a", 1, 10),
                        List.of(row("chunk-a", "en"))
                );

        assertThat(report.enabled()).isFalse();
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

    @Test
    void linkingRejectsSelfAndHonorsPerChunkBudget() {
        SemanticMemoryProperties properties = enabledProperties();
        AdaptiveGraphProperties graphProperties = mock(AdaptiveGraphProperties.class);
        when(graphProperties.graphVersion()).thenReturn(7);

        SemanticNeighborSearchRepository neighbors =
                mock(SemanticNeighborSearchRepository.class);
        SemanticAssociationSeedRepository seeds =
                mock(SemanticAssociationSeedRepository.class);
        IngestionSemanticLinker linker = new IngestionSemanticLinker(
                properties,
                graphProperties,
                neighbors,
                seeds
        );

        ChunkGraphNode self = new ChunkGraphNode(10, "doc-a", 1, "chunk-a");
        ChunkGraphNode first = new ChunkGraphNode(10, "doc-b", 2, "chunk-b");
        ChunkGraphNode second = new ChunkGraphNode(10, "doc-c", 3, "chunk-c");
        ChunkGraphNode overBudget = new ChunkGraphNode(10, "doc-d", 4, "chunk-d");

        when(neighbors.search(
                any(float[].class),
                anyString(),
                anyLong(),
                anyInt(),
                anyDouble(),
                anyBoolean()
        )).thenReturn(List.of(
                new SemanticNeighborSearchRepository.SemanticNeighbor(self, "en", 1.0),
                new SemanticNeighborSearchRepository.SemanticNeighbor(first, "en", 0.97),
                new SemanticNeighborSearchRepository.SemanticNeighbor(second, "en", 0.95),
                new SemanticNeighborSearchRepository.SemanticNeighbor(overBudget, "en", 0.94)
        ));
        when(seeds.seedSymmetric(
                any(), any(), anyDouble(), anyInt(), anyInt(), any()
        )).thenReturn(true);

        IngestionSemanticLinker.LinkingReport report =
                linker.linkPublishedGeneration(
                        new GenerationIdentity("doc-a", 1, 10),
                        List.of(row("chunk-a", "en"))
                );

        assertThat(report.enabled()).isTrue();
        assertThat(report.searchedChunks()).isEqualTo(1);
        assertThat(report.seededEdges()).isEqualTo(2);
        assertThat(report.selfRejected()).isEqualTo(1);
        assertThat(report.budgetRejected()).isEqualTo(1);
        assertThat(report.degreeRejected()).isZero();
        verify(seeds).seedSymmetric(
                self,
                first,
                0.97,
                7,
                2,
                any()
        );
        verify(seeds).seedSymmetric(
                self,
                second,
                0.95,
                7,
                2,
                any()
        );
        verify(seeds, never()).seedSymmetric(
                self,
                overBudget,
                0.94,
                7,
                2,
                any()
        );
    }

    @Test
    void degreeRejectionDoesNotConsumeSuccessfulEdgeBudget() {
        SemanticMemoryProperties properties = enabledProperties();
        AdaptiveGraphProperties graphProperties = mock(AdaptiveGraphProperties.class);
        when(graphProperties.graphVersion()).thenReturn(3);

        SemanticNeighborSearchRepository neighbors =
                mock(SemanticNeighborSearchRepository.class);
        SemanticAssociationSeedRepository seeds =
                mock(SemanticAssociationSeedRepository.class);
        IngestionSemanticLinker linker = new IngestionSemanticLinker(
                properties,
                graphProperties,
                neighbors,
                seeds
        );

        ChunkGraphNode saturated = new ChunkGraphNode(10, "hub", 1, "hub-1");
        ChunkGraphNode accepted = new ChunkGraphNode(10, "doc-b", 1, "chunk-b");
        when(neighbors.search(
                any(float[].class),
                anyString(),
                anyLong(),
                anyInt(),
                anyDouble(),
                anyBoolean()
        )).thenReturn(List.of(
                new SemanticNeighborSearchRepository.SemanticNeighbor(
                        saturated, "en", 0.99
                ),
                new SemanticNeighborSearchRepository.SemanticNeighbor(
                        accepted, "en", 0.96
                )
        ));
        when(seeds.seedSymmetric(
                any(), any(), anyDouble(), anyInt(), anyInt(), any()
        )).thenReturn(false, true);

        IngestionSemanticLinker.LinkingReport report =
                linker.linkPublishedGeneration(
                        new GenerationIdentity("doc-a", 1, 10),
                        List.of(row("chunk-a", "en"))
                );

        assertThat(report.seededEdges()).isEqualTo(1);
        assertThat(report.degreeRejected()).isEqualTo(1);
        assertThat(report.budgetRejected()).isZero();
    }

    private SemanticMemoryProperties enabledProperties() {
        SemanticMemoryProperties properties = new SemanticMemoryProperties();
        properties.setIngestionLinkingEnabled(true);
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
