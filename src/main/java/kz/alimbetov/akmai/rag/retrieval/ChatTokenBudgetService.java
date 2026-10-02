package kz.alimbetov.akmai.rag.retrieval;

import kz.alimbetov.akmai.rag.service.RagPromptTemplate;
import kz.alimbetov.akmai.token.ModelTokenBudgetRegistry;
import org.springframework.stereotype.Component;

@Component
public class ChatTokenBudgetService {

    private final ModelTokenBudgetRegistry registry;
    private final RetrievalProperties properties;
    private final RagPromptTemplate promptTemplate;

    public ChatTokenBudgetService(
            ModelTokenBudgetRegistry registry,
            RetrievalProperties properties,
            RagPromptTemplate promptTemplate
    ) {
        this.registry = registry;
        this.properties = properties;
        this.promptTemplate = promptTemplate;
    }

    public boolean fits(String question, String contextJson) {
        int system = registry.counter().upperBound(
                promptTemplate.systemPrompt()
        );
        int user = registry.counter().upperBound(
                promptTemplate.userPrompt(question, contextJson)
        );
        long required = (long) system
                + user
                + properties.answerReservedTokens();
        return required <= registry.chatContextWindow();
    }

    public int upperBoundInputTokens(
            String question,
            String contextJson
    ) {
        return Math.addExact(
                registry.counter().upperBound(
                        promptTemplate.systemPrompt()
                ),
                registry.counter().upperBound(
                        promptTemplate.userPrompt(question, contextJson)
                )
        );
    }
}
