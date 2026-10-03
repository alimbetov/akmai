package kz.alimbetov.akmai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.ExecutorService;
import kz.alimbetov.akmai.config.BoundedExecutorFactory;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierExtractor;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class ParallelIngestionExecutorTest {

    @Test
    void largeDocumentDoesNotSelfRejectOnBoundedExecutor() {
        ExecutorService executor = BoundedExecutorFactory.create(2, 1);
        IdentifierExtractor identifiers = mock(IdentifierExtractor.class);
        when(identifiers.extract(anyString())).thenAnswer(invocation -> {
            Thread.sleep(2);
            return List.of();
        });

        ParallelIngestionExecutor service =
                new ParallelIngestionExecutor(identifiers, executor);

        List<KnowledgeChunk> chunks = java.util.stream.IntStream
                .range(0, 200)
                .mapToObj(this::chunk)
                .toList();

        try {
            List<EnrichedKnowledgeChunk> result = service.execute(chunks);

            assertThat(result).hasSize(200);
            assertThat(result)
                    .extracting(value -> value.chunk().chunkIndex())
                    .containsExactlyElementsOf(
                            java.util.stream.IntStream.range(0, 200)
                                    .boxed()
                                    .toList()
                    );
        } finally {
            executor.shutdownNow();
        }
    }

    private KnowledgeChunk chunk(int index) {
        return new KnowledgeChunk(
                "chunk-" + index,
                "doc-large",
                null,
                index,
                "raw " + index,
                "raw " + index,
                "embedding " + index,
                "Title",
                "Section",
                "en",
                KnowledgeDomain.GENERAL,
                List.of(),
                java.util.Map.of()
        );
    }
}
