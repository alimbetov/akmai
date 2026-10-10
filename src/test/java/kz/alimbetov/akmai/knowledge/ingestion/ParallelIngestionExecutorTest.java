package kz.alimbetov.akmai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void inFlightEnrichmentIsBoundedBySharedExecutorCapacity() throws Exception {
        ExecutorService ingestionExecutor = BoundedExecutorFactory.create(3, 3);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        IdentifierExtractor identifiers = mock(IdentifierExtractor.class);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch firstWaveEntered = new CountDownLatch(3);
        CountDownLatch releaseFirstWave = new CountDownLatch(1);

        when(identifiers.extract(anyString())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            firstWaveEntered.countDown();
            try {
                releaseFirstWave.await();
                return List.of();
            } finally {
                active.decrementAndGet();
            }
        });

        ParallelIngestionExecutor service =
                new ParallelIngestionExecutor(identifiers, ingestionExecutor);
        List<KnowledgeChunk> chunks = java.util.stream.IntStream
                .range(0, 30)
                .mapToObj(this::chunk)
                .toList();

        try {
            Future<List<EnrichedKnowledgeChunk>> result =
                    caller.submit(() -> service.execute(chunks));

            assertThat(firstWaveEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(active.get()).isEqualTo(3);
            assertThat(maxActive.get()).isEqualTo(3);

            releaseFirstWave.countDown();

            assertThat(result.get(10, TimeUnit.SECONDS)).hasSize(30);
            assertThat(maxActive.get()).isLessThanOrEqualTo(3);
        } finally {
            releaseFirstWave.countDown();
            caller.shutdownNow();
            ingestionExecutor.shutdownNow();
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
