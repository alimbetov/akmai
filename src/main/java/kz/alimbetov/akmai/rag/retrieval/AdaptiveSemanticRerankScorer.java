package kz.alimbetov.akmai.rag.retrieval;

import java.util.List;
import kz.alimbetov.akmai.rag.query.AdvancedRetrievalProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class AdaptiveSemanticRerankScorer implements SemanticRerankScorer {

    private final EmbeddingSemanticRerankScorer fallback;
    private final AdvancedRetrievalProperties properties;
    private final ObjectProvider<ColbertRerankClient> colbertClientProvider;

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
        if (client == null) {
            return fallback.score(question, hits);
        }

        try {
            List<Double> scores = client.score(question, hits);
            validate(scores, hits.size());
            return List.copyOf(scores);
        } catch (RuntimeException exception) {
            return fallback.score(question, hits);
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
