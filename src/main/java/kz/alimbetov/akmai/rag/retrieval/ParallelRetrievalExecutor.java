package kz.alimbetov.akmai.rag.retrieval;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalStep;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ParallelRetrievalExecutor {

    private final Map<RetrievalType, RetrievalStrategy> strategies;
    private final Executor retrievalExecutor;

    public ParallelRetrievalExecutor(
            List<RetrievalStrategy> strategies,
            @Qualifier("retrievalExecutor") Executor retrievalExecutor
    ) {
        this.strategies = new EnumMap<>(RetrievalType.class);
        strategies.forEach(strategy -> this.strategies.put(strategy.type(), strategy));
        this.retrievalExecutor = retrievalExecutor;
    }

    public List<RetrievalHit> execute(RetrievalPlan plan) {
        validateAcyclic(plan);
        Map<String, CompletableFuture<List<RetrievalHit>>> futures = new HashMap<>();

        for (RetrievalStep step : plan.steps()) {
            schedule(step, plan, futures);
        }

        return plan.steps().stream()
                .flatMap(step -> isolateFailure(futures.get(step.id()))
                        .join()
                        .stream())
                .toList();
    }

    private CompletableFuture<List<RetrievalHit>> schedule(
            RetrievalStep step,
            RetrievalPlan plan,
            Map<String, CompletableFuture<List<RetrievalHit>>> futures
    ) {
        CompletableFuture<List<RetrievalHit>> existing = futures.get(step.id());
        if (existing != null) {
            return existing;
        }

        List<CompletableFuture<List<RetrievalHit>>> dependencies = step.dependsOn().stream()
                .map(id -> findStep(plan, id))
                .map(dependency -> schedule(dependency, plan, futures))
                .toList();

        List<CompletableFuture<List<RetrievalHit>>> isolatedDependencies =
                dependencies.stream()
                        .map(this::isolateFailure)
                        .toList();

        CompletableFuture<Void> ready = CompletableFuture.allOf(
                isolatedDependencies.toArray(CompletableFuture[]::new)
        );

        CompletableFuture<List<RetrievalHit>> future = ready.thenApplyAsync(
                ignored -> executeStep(step, isolatedDependencies),
                retrievalExecutor
        );
        futures.put(step.id(), future);
        return future;
    }

    private CompletableFuture<List<RetrievalHit>> isolateFailure(
            CompletableFuture<List<RetrievalHit>> future
    ) {
        return future.exceptionally(ignored -> List.of());
    }

    private List<RetrievalHit> executeStep(
            RetrievalStep step,
            List<CompletableFuture<List<RetrievalHit>>> dependencies
    ) {
        RetrievalStrategy strategy = strategies.get(step.type());
        if (strategy == null) {
            return List.of();
        }

        List<RetrievalHit> dependencyHits = new ArrayList<>();
        dependencies.forEach(future -> dependencyHits.addAll(future.join()));

        return strategy.retrieve(
                        step.queryChunk(),
                        new RetrievalContext(List.copyOf(dependencyHits))
                ).stream()
                .map(hit -> withQueryChunk(hit, step.queryChunk().id()))
                .toList();
    }

    private RetrievalHit withQueryChunk(RetrievalHit hit, String queryChunkId) {
        Map<String, Object> metadata = new HashMap<>(hit.metadata());
        metadata.put("queryChunkId", queryChunkId);
        return new RetrievalHit(
                hit.type(),
                hit.documentId(),
                hit.chunkId(),
                hit.text(),
                metadata,
                hit.evidence(),
                hit.fusedScore()
        );
    }

    private void validateAcyclic(RetrievalPlan plan) {
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (RetrievalStep step : plan.steps()) {
            visit(step, plan, visited, visiting);
        }
    }

    private void visit(
            RetrievalStep step,
            RetrievalPlan plan,
            Set<String> visited,
            Set<String> visiting
    ) {
        if (visited.contains(step.id())) {
            return;
        }
        if (!visiting.add(step.id())) {
            throw new IllegalArgumentException(
                    "Retrieval plan contains a dependency cycle at step: " + step.id()
            );
        }
        for (String dependencyId : step.dependsOn()) {
            visit(findStep(plan, dependencyId), plan, visited, visiting);
        }
        visiting.remove(step.id());
        visited.add(step.id());
    }

    private RetrievalStep findStep(RetrievalPlan plan, String id) {
        return plan.steps().stream()
                .filter(step -> step.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown retrieval dependency: " + id
                ));
    }
}
