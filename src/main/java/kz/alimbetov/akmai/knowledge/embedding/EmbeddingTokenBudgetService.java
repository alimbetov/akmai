package kz.alimbetov.akmai.knowledge.embedding;

import kz.alimbetov.akmai.token.ModelTokenBudgetRegistry;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingTokenBudgetService {

    private final ModelTokenBudgetRegistry registry;

    public EmbeddingTokenBudgetService(ModelTokenBudgetRegistry registry) {
        this.registry = registry;
    }

    public void assertFits(String embeddingText) {
        int upperBound = registry.counter().upperBound(embeddingText);
        if (upperBound > registry.embeddingContextWindow()) {
            throw new IllegalArgumentException(
                    "Embedding payload exceeds configured model context window"
            );
        }
    }
}
