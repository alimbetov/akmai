package kz.alimbetov.akmai.rag.retrieval;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
@Service
public class ParallelRetrievalExecutor {
    private final List<RetrievalStrategy> strategies;
    private final Executor retrievalExecutor;
    public ParallelRetrievalExecutor(List<RetrievalStrategy> strategies, @Qualifier("retrievalExecutor") Executor retrievalExecutor) {
        this.strategies = strategies;
        this.retrievalExecutor = retrievalExecutor;
    }
    public List<RetrievalHit> execute(List<QueryChunk> chunks) {
        List<CompletableFuture<List<RetrievalHit>>> futures = chunks.stream()
                .flatMap(chunk -> strategies.stream().filter(strategy -> strategy.supports(chunk))
                        .map(strategy -> CompletableFuture.supplyAsync(() -> strategy.retrieve(chunk), retrievalExecutor)))
                .toList();
        return futures.stream().flatMap(future -> future.join().stream()).toList();
    }
}
