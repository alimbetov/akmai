package kz.alimbetov.akmai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import kz.alimbetov.akmai.config.BoundedExecutorFactory;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierExtractor;
import kz.alimbetov.akmai.knowledge.model.ChunkRole;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.semantic.SemanticChunkAnnotator;
import org.junit.jupiter.api.Test;

class ParallelIngestionExecutorFailureModelTest {

    @Test
    void nullAndEmptyInputReturnEmptyWithoutSchedulingWork() {
        IdentifierExtractor identifiers = mock(IdentifierExtractor.class);
        java.util.concurrent.Executor executor = mock(java.util.concurrent.Executor.class);
        ParallelIngestionExecutor service = new ParallelIngestionExecutor(
                identifiers,
                executor,
                null
        );

        assertThat(service.execute(null)).isEmpty();
        assertThat(service.execute(List.of())).isEmpty();

        verifyNoInteractions(identifiers, executor);
    }

    @Test
    void identifierFailureIsPropagatedToIngestionCaller() {
        ExecutorService executor = BoundedExecutorFactory.create(2, 2);
        IdentifierExtractor identifiers = mock(IdentifierExtractor.class);
        when(identifiers.extract(anyString()))
                .thenThrow(new IllegalStateException("identifier extraction failed"));
        ParallelIngestionExecutor service = new ParallelIngestionExecutor(
                identifiers,
                executor,
                null
        );

        try {
            assertThatThrownBy(() -> service.execute(List.of(chunk("child", Map.of()))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("identifier extraction failed");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void semanticAnnotationFailureStopsBeforeIdentifierExtraction() {
        ExecutorService executor = BoundedExecutorFactory.create(1, 1);
        IdentifierExtractor identifiers = mock(IdentifierExtractor.class);
        SemanticChunkAnnotator annotator = mock(SemanticChunkAnnotator.class);
        KnowledgeChunk chunk = chunk("child", Map.of());
        when(annotator.annotate(chunk))
                .thenThrow(new IllegalArgumentException("semantic annotation failed"));
        ParallelIngestionExecutor service = new ParallelIngestionExecutor(
                identifiers,
                executor,
                annotator
        );

        try {
            assertThatThrownBy(() -> service.execute(List.of(chunk)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("semantic annotation failed");
            verify(identifiers, never()).extract(anyString());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void parentChunkBypassesSemanticAndIdentifierEnrichment() {
        ExecutorService executor = BoundedExecutorFactory.create(1, 1);
        IdentifierExtractor identifiers = mock(IdentifierExtractor.class);
        SemanticChunkAnnotator annotator = mock(SemanticChunkAnnotator.class);
        KnowledgeChunk parent = chunk(
                "parent",
                Map.of(ChunkRole.METADATA_KEY, ChunkRole.PARENT.name())
        );
        ParallelIngestionExecutor service = new ParallelIngestionExecutor(
                identifiers,
                executor,
                annotator
        );

        try {
            List<EnrichedKnowledgeChunk> result = service.execute(List.of(parent));
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().chunk()).isSameAs(parent);
            assertThat(result.getFirst().identifiers()).isEmpty();
            verify(annotator, never()).annotate(any());
            verify(identifiers, never()).extract(anyString());
        } finally {
            executor.shutdownNow();
        }
    }

    private KnowledgeChunk chunk(String id, Map<String, Object> metadata) {
        return new KnowledgeChunk(
                id,
                "doc-1",
                null,
                0,
                "raw",
                "normalized",
                "embedding",
                "Title",
                "Section",
                "en",
                KnowledgeDomain.GENERAL,
                List.of(),
                metadata
        );
    }
}
