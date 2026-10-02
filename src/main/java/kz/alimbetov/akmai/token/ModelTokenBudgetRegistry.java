package kz.alimbetov.akmai.token;

import kz.alimbetov.akmai.config.ModelBudgetProperties;
import org.springframework.stereotype.Component;

@Component
public class ModelTokenBudgetRegistry {

    private final TokenUpperBoundCounter counter;
    private final ModelBudgetProperties properties;

    public ModelTokenBudgetRegistry(
            TokenUpperBoundCounter counter,
            ModelBudgetProperties properties
    ) {
        this.counter = counter;
        this.properties = properties;
    }

    public TokenUpperBoundCounter counter() {
        return counter;
    }

    public int chatContextWindow() {
        return properties.chatContextWindow();
    }

    public int embeddingContextWindow() {
        return properties.embeddingContextWindow();
    }
}
