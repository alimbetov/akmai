package kz.alimbetov.akmai.knowledge.ingestion;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierExtractor;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ParallelIngestionExecutor {

    private final IdentifierExtractor identifierExtractor;
    private final Executor ingestionExecutor;

    public ParallelIngestionExecutor(
            IdentifierExtractor identifierExtractor,
            @Qualifier("ingestionExecutor") Executor ingestionExecutor
    ) {
        this.identifierExtractor = identifierExtractor;
        this.ingestionExecutor = ingestionExecutor;
    }

    public List<EnrichedKnowledgeChunk> execute(List<KnowledgeChunk> chunks) {
        List<CompletableFuture<EnrichedKnowledgeChunk>> futures = chunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(
                        () -> enrich(chunk),
                        ingestionExecutor
                ))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    private EnrichedKnowledgeChunk enrich(KnowledgeChunk chunk) {
        // Chunks are already processed concurrently by the bounded executor.
        // Do not submit nested work to the same fixed pool: that can deadlock
        // when every worker waits for another task from the saturated pool.
        return new EnrichedKnowledgeChunk(
                chunk,
                identifierExtractor.extract(chunk.rawText()),
                chunk.references()
        );
    }
}
