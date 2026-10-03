package kz.alimbetov.akmai.knowledge.ingestion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierExtractor;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ParallelIngestionExecutor {

    private final IdentifierExtractor identifierExtractor;
    private final Executor ingestionExecutor;
    private final int maxInFlight;

    public ParallelIngestionExecutor(
            IdentifierExtractor identifierExtractor,
            @Qualifier("ingestionExecutor") Executor ingestionExecutor
    ) {
        this.identifierExtractor = identifierExtractor;
        this.ingestionExecutor = ingestionExecutor;
        this.maxInFlight = ingestionExecutor instanceof ThreadPoolExecutor pool
                ? Math.max(1, pool.getMaximumPoolSize())
                : 1;
    }

    public List<EnrichedKnowledgeChunk> execute(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        ExecutorCompletionService<IndexedChunk> completion =
                new ExecutorCompletionService<>(ingestionExecutor);
        List<Future<IndexedChunk>> submitted =
                new ArrayList<>(Math.min(chunks.size(), maxInFlight));
        List<EnrichedKnowledgeChunk> ordered = new ArrayList<>(
                Collections.nCopies(chunks.size(), null)
        );

        int next = 0;
        int inFlight = 0;
        int completed = 0;

        try {
            while (next < chunks.size() && inFlight < maxInFlight) {
                submitted.add(submit(completion, chunks, next++));
                inFlight++;
            }

            while (completed < chunks.size()) {
                Future<IndexedChunk> future = completion.take();
                inFlight--;
                IndexedChunk result = future.get();
                ordered.set(result.index(), result.value());
                completed++;

                if (next < chunks.size()) {
                    submitted.add(submit(completion, chunks, next++));
                    inFlight++;
                }
            }
        } catch (InterruptedException exception) {
            cancel(submitted);
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Ingestion enrichment was interrupted",
                    exception
            );
        } catch (ExecutionException exception) {
            cancel(submitted);
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(
                    "Ingestion enrichment failed",
                    cause
            );
        } catch (RuntimeException exception) {
            cancel(submitted);
            throw exception;
        }

        return List.copyOf(ordered);
    }

    private Future<IndexedChunk> submit(
            ExecutorCompletionService<IndexedChunk> completion,
            List<KnowledgeChunk> chunks,
            int index
    ) {
        return completion.submit(() ->
                new IndexedChunk(index, enrich(chunks.get(index)))
        );
    }

    private void cancel(List<Future<IndexedChunk>> futures) {
        futures.forEach(future -> future.cancel(true));
    }

    private EnrichedKnowledgeChunk enrich(KnowledgeChunk chunk) {
        return new EnrichedKnowledgeChunk(
                chunk,
                identifierExtractor.extract(chunk.rawText()),
                chunk.references()
        );
    }

    private record IndexedChunk(
            int index,
            EnrichedKnowledgeChunk value
    ) {
    }
}
