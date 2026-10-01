package kz.alimbetov.akmai.rag.retrieval;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class Reranker {

    private final SemanticRerankScorer scorer;
    private final RetrievalProperties properties;
    private final RetrievalObserver observer;
    private final ExecutorService executor;

    public Reranker(
            SemanticRerankScorer scorer,
            RetrievalProperties properties,
            RetrievalObserver observer,
            @Qualifier("rerankerExecutor") ExecutorService executor
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
        Future<List<RetrievalHit>> future;

        try {
            future = executor.submit(() -> score(candidates, question));
        } catch (RuntimeException exception) {
            observer.rerankFailure(Duration.between(started, Instant.now()), exception);
            return hits;
        }

        try {
            List<RetrievalHit> reranked = future.get(
                    properties.rerankerTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
            observer.rerankSuccess(
                    Duration.between(started, Instant.now()),
                    candidateCount
            );
            List<RetrievalHit> result = new ArrayList<>(hits.size());
            result.addAll(reranked);
            result.addAll(tail);
            return List.copyOf(result);
        } catch (TimeoutException exception) {
            future.cancel(true);
            observer.rerankFailure(Duration.between(started, Instant.now()), exception);
            return hits;
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            observer.rerankFailure(Duration.between(started, Instant.now()), exception);
            return hits;
        } catch (ExecutionException exception) {
            observer.rerankFailure(Duration.between(started, Instant.now()), exception);
            return hits;
        } catch (RuntimeException exception) {
            future.cancel(true);
            observer.rerankFailure(Duration.between(started, Instant.now()), exception);
            return hits;
        }
    }

    private List<RetrievalHit> score(List<RetrievalHit> candidates, String question) {
        double maxFused = candidates.stream()
                .mapToDouble(RetrievalHit::fusedScore)
                .max()
                .orElse(0.0);
        List<Double> semanticScores = scorer.score(question, candidates);
        if (semanticScores.size() != candidates.size()) {
            throw new IllegalStateException("Rerank scorer returned unexpected score count");
        }
        List<RetrievalHit> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            scored.add(withRerankScore(
                    candidates.get(i),
                    semanticScores.get(i),
                    maxFused
            ));
        }
        return scored.stream()
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
