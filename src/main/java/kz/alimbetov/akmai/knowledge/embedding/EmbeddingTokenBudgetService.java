package kz.alimbetov.akmai.knowledge.embedding;

import kz.alimbetov.akmai.token.ModelTokenBudgetRegistry;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingTokenBudgetService {

    private final ModelTokenBudgetRegistry registry;

    public EmbeddingTokenBudgetService(ModelTokenBudgetRegistry registry) {
        this.registry = registry;
    }

    public boolean fits(String embeddingText) {
        return upperBound(embeddingText) <= contextWindow();
    }

    public int upperBound(String embeddingText) {
        return registry.counter().upperBound(embeddingText);
    }

    public int contextWindow() {
        return registry.embeddingContextWindow();
    }

    public void assertFits(String embeddingText) {
        int upperBound = upperBound(embeddingText);
        if (upperBound > contextWindow()) {
            throw new IllegalArgumentException(
                    "Embedding payload exceeds configured model context window"
            );
        }
    }
}
