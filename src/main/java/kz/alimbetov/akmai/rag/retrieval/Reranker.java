package kz.alimbetov.akmai.rag.retrieval;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.semantic.SemanticConceptMatch;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalysis;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalyzer;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class Reranker {

    private static final double CONCEPT_BOOST_PER_MATCH = 0.02;
    private static final double MAX_CONCEPT_BOOST = 0.06;

    private final SemanticRerankScorer scorer;
    private final RetrievalProperties properties;
    private final RetrievalObserver observer;
    private final ExecutorService executor;
    private final SemanticQueryAnalyzer semanticQueryAnalyzer;

    public Reranker(
            SemanticRerankScorer scorer,
            RetrievalProperties properties,
            RetrievalObserver observer,
            @Qualifier("rerankerExecutor") ExecutorService executor
    ) {
        this(
                scorer,
                properties,
                observer,
                executor,
                null
        );
    }

    @Autowired
    public Reranker(
            SemanticRerankScorer scorer,
            RetrievalProperties properties,
            RetrievalObserver observer,
            @Qualifier("rerankerExecutor") ExecutorService executor,
            SemanticQueryAnalyzer semanticQueryAnalyzer
    ) {
        this.scorer = scorer;
        this.properties = properties;
        this.observer = observer;
        this.executor = executor;
        this.semanticQueryAnalyzer = semanticQueryAnalyzer;
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
                    properties.rerankerTimeout().toNanos(),
                    TimeUnit.NANOSECONDS
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
        } catch (ExecutionException | RuntimeException exception) {
            future.cancel(true);
            observer.rerankFailure(Duration.between(started, Instant.now()), exception);
            return hits;
        }
    }

    private List<RetrievalHit> score(List<RetrievalHit> candidates, String question) {
        double maxFused = candidates.stream()
                .mapToDouble(RetrievalHit::fusedScore)
                .filter(Double::isFinite)
                .max()
                .orElse(0.0);
        SemanticQueryAnalysis semanticQuery =
                analyzeSemanticQuery(question);
        Set<String> queryConceptIds =
                semanticQuery == null
                        ? Set.of()
                        : semanticQuery.concepts().stream()
                                .map(SemanticConceptMatch::conceptId)
                                .collect(Collectors.toUnmodifiableSet());

        List<Double> semanticScores = scorer.score(question, candidates);
        if (semanticScores.size() != candidates.size()) {
            throw new IllegalStateException(
                    "Rerank scorer returned unexpected score count"
            );
        }
        List<RetrievalHit> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            double semantic = semanticScores.get(i);
            if (!Double.isFinite(semantic)) {
                throw new IllegalStateException("Rerank scorer returned non-finite score");
            }
            ConceptBoost conceptBoost = conceptBoost(
                    candidates.get(i),
                    semanticQuery,
                    queryConceptIds
            );
            scored.add(withRerankScore(
                    candidates.get(i),
                    semantic,
                    maxFused,
                    conceptBoost
            ));
        }
        return scored.stream()
                .sorted(
                        Comparator.comparingInt(this::authorityTier)
                                .thenComparing(
                                        Comparator.comparingDouble(
                                                this::rerankScore
                                        ).reversed()
                                )
                )
                .toList();
    }

    private RetrievalHit withRerankScore(
            RetrievalHit hit,
            double semanticScore,
            double maxFused,
            ConceptBoost conceptBoost
    ) {
        double normalizedFused = maxFused <= 0.0
                ? 0.0
                : hit.fusedScore() / maxFused;
        double weight = properties.rerankerFusedWeight();
        double base = semanticScore * (1.0 - weight)
                + normalizedFused * weight;
        double combined = Math.max(
                -1.0,
                Math.min(1.0, base + conceptBoost.value())
        );
        if (!Double.isFinite(combined)) {
            throw new IllegalStateException("Combined rerank score is not finite");
        }
        Map<String, Object> metadata = new HashMap<>(hit.metadata());
        metadata.put("rerankSemanticScore", semanticScore);
        metadata.put(
                "rerankConceptOverlap",
                conceptBoost.overlapCount()
        );
        metadata.put(
                "rerankConceptBoost",
                conceptBoost.value()
        );
        metadata.put("rerankScore", combined);
        return new RetrievalHit(
                hit.type(),
                hit.accessLevel(),
                hit.documentId(),
                hit.generation(),
                hit.chunkId(),
                hit.text(),
                metadata,
                hit.evidence(),
                hit.fusedScore()
        );
    }

    private SemanticQueryAnalysis analyzeSemanticQuery(
            String question
    ) {
        if (semanticQueryAnalyzer == null) {
            return null;
        }
        try {
            return semanticQueryAnalyzer.analyze(question);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private ConceptBoost conceptBoost(
            RetrievalHit hit,
            SemanticQueryAnalysis semanticQuery,
            Set<String> queryConceptIds
    ) {
        if (semanticQuery == null
                || queryConceptIds.isEmpty()) {
            return ConceptBoost.NONE;
        }

        Object raw = hit.metadata().get("semanticConcepts");
        if (!(raw instanceof Iterable<?> values)) {
            return ConceptBoost.NONE;
        }

        int overlap = 0;
        for (Object value : values) {
            if (value instanceof String conceptId
                    && queryConceptIds.contains(conceptId)) {
                overlap++;
            }
        }
        if (overlap == 0) {
            return ConceptBoost.NONE;
        }

        double confidence = Math.max(
                0.0,
                Math.min(1.0, semanticQuery.confidence())
        );
        double boost = Math.min(
                MAX_CONCEPT_BOOST,
                overlap * CONCEPT_BOOST_PER_MATCH
        ) * confidence;
        return new ConceptBoost(overlap, boost);
    }

    private int authorityTier(RetrievalHit hit) {
        Object tier = hit.metadata().get("authorityTier");
        return tier instanceof Number number
                ? Math.max(0, number.intValue())
                : 2;
    }

    private record ConceptBoost(int overlapCount, double value) {
        private static final ConceptBoost NONE = new ConceptBoost(0, 0.0);
    }

    private double rerankScore(RetrievalHit hit) {
        Object score = hit.metadata().get("rerankScore");
        if (score instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        return 0.0;
    }
}
