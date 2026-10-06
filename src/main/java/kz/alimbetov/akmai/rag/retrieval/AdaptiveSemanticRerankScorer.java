package kz.alimbetov.akmai.rag.retrieval;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import kz.alimbetov.akmai.rag.query.AdvancedRetrievalProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class AdaptiveSemanticRerankScorer implements SemanticRerankScorer {

    private static final int FAILURE_THRESHOLD = 3;
    private static final long OPEN_NANOS = Duration.ofSeconds(30).toNanos();

    private final EmbeddingSemanticRerankScorer fallback;
    private final AdvancedRetrievalProperties properties;
    private final ObjectProvider<ColbertRerankClient> colbertClientProvider;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntilNanos = new AtomicLong();
    private final AtomicBoolean halfOpenProbe = new AtomicBoolean();

    public AdaptiveSemanticRerankScorer(
            EmbeddingSemanticRerankScorer fallback,
            AdvancedRetrievalProperties properties,
            ObjectProvider<ColbertRerankClient> colbertClientProvider
    ) {
        this.fallback = fallback;
        this.properties = properties;
        this.colbertClientProvider = colbertClientProvider;
    }

    @Override
    public List<Double> score(
            String question,
            List<RetrievalHit> hits
    ) {
        if (!properties.colbertEnabled() || hits.isEmpty()) {
            return fallback.score(question, hits);
        }

        ColbertRerankClient client = colbertClientProvider.getIfAvailable();
        if (client == null || !allowColbertCall()) {
            return fallback.score(question, hits);
        }

        try {
            List<Double> scores = client.score(question, hits);
            validate(scores, hits.size());
            onSuccess();
            return List.copyOf(scores);
        } catch (RuntimeException exception) {
            onFailure();
            return fallback.score(question, hits);
        }
    }

    private boolean allowColbertCall() {
        long until = openUntilNanos.get();
        if (until == 0L) {
            return true;
        }
        long now = System.nanoTime();
        if (now < until) {
            return false;
        }
        return halfOpenProbe.compareAndSet(false, true);
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
        openUntilNanos.set(0L);
        halfOpenProbe.set(false);
    }

    private void onFailure() {
        halfOpenProbe.set(false);
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD) {
            openUntilNanos.set(System.nanoTime() + OPEN_NANOS);
        }
    }

    private void validate(List<Double> scores, int expectedSize) {
        if (scores == null || scores.size() != expectedSize) {
            throw new IllegalStateException(
                    "ColBERT scorer returned unexpected score count"
            );
        }
        for (Double score : scores) {
            if (score == null || !Double.isFinite(score)) {
                throw new IllegalStateException(
                        "ColBERT scorer returned non-finite score"
                );
            }
        }
    }
}
