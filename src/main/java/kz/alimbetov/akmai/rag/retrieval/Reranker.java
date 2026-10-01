package kz.alimbetov.akmai.rag.retrieval;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class Reranker {

    private final SemanticRerankScorer scorer;
    private final RetrievalProperties properties;
    private final RetrievalObserver observer;
    private final Executor executor;

    public Reranker(
            SemanticRerankScorer scorer,
            RetrievalProperties properties,
            RetrievalObserver observer,
            @Qualifier("rerankerExecutor") Executor executor
    ) {
        this.scorer = scorer;
        this.properties = properties;
        this.observer = observer;
        this.executor = executor;
    }

    public List<RetrievalHit> rerank(List<RetrievalHit> hits, String question) {
        if (!properties.rerankerEnabled() || hits.size() < 2) {
            return hits;
        }

        int candidateCount = Math.min(properties.rerankerCandidates(), hits.size());
        List<RetrievalHit> candidates = hits.subList(0, candidateCount);
        List<RetrievalHit> tail = hits.subList(candidateCount, hits.size());
        Instant started = Instant.now();

        try {
            List<RetrievalHit> reranked = CompletableFuture.supplyAsync(
                            () -> score(candidates, question),
                            executor
                    )
                    .orTimeout(properties.rerankerTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            observer.rerankSuccess(
                    Duration.between(started, Instant.now()),
                    candidateCount
            );
            List<RetrievalHit> result = new ArrayList<>(hits.size());
            result.addAll(reranked);
            result.addAll(tail);
            return List.copyOf(result);
        } catch (RuntimeException exception) {
            observer.rerankFailure(Duration.between(started, Instant.now()), exception);
            return hits;
        }
    }

    private List<RetrievalHit> score(List<RetrievalHit> candidates, String question) {
        double maxFused = candidates.stream()
                .mapToDouble(RetrievalHit::fusedScore)
                .max()
                .orElse(0.0);
        return candidates.stream()
                .map(hit -> withRerankScore(
                        hit,
                        scorer.score(question, hit),
                        maxFused
                ))
                .sorted(Comparator.comparingDouble(this::rerankScore).reversed())
                .toList();
    }

    private RetrievalHit withRerankScore(
            RetrievalHit hit,
            double semanticScore,
            double maxFused
    ) {
        double normalizedFused = maxFused <= 0.0 ? 0.0 : hit.fusedScore() / maxFused;
        double weight = properties.rerankerFusedWeight();
        double combined = semanticScore * (1.0 - weight) + normalizedFused * weight;
        Map<String, Object> metadata = new HashMap<>(hit.metadata());
        metadata.put("rerankSemanticScore", semanticScore);
        metadata.put("rerankScore", combined);
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

    private double rerankScore(RetrievalHit hit) {
        Object score = hit.metadata().get("rerankScore");
        return score instanceof Number number ? number.doubleValue() : 0.0;
    }
}
