package kz.alimbetov.akmai.rag.service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kz.alimbetov.akmai.rag.retrieval.RetrievalProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AnswerGenerationService {

    private final ChatClient chatClient;
    private final ExecutorService executor;
    private final RetrievalProperties properties;
    private final RagPromptTemplate promptTemplate;

    public AnswerGenerationService(
            ChatClient.Builder builder,
            @Qualifier("answerExecutor") ExecutorService executor,
            RetrievalProperties properties,
            RagPromptTemplate promptTemplate
    ) {
        this.chatClient = builder.build();
        this.executor = executor;
        this.properties = properties;
        this.promptTemplate = promptTemplate;
    }

    public String generate(String question, String contextJson) {
        Future<String> future;
        try {
            future = executor.submit(() -> callModel(question, contextJson));
        } catch (RejectedExecutionException exception) {
            throw new AnswerGenerationException(
                    "Answer generation is overloaded",
                    exception
            );
        }

        try {
            return future.get(
                    properties.answerTimeout().toNanos(),
                    TimeUnit.NANOSECONDS
            );
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new AnswerGenerationException(
                    "Answer generation timed out",
                    exception
            );
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AnswerGenerationException(
                    "Answer generation was interrupted",
                    exception
            );
        } catch (ExecutionException exception) {
            throw new AnswerGenerationException(
                    "Answer generation failed",
                    exception.getCause()
            );
        }
    }

    private String callModel(String question, String contextJson) {
        return chatClient.prompt()
                .system(promptTemplate.systemPrompt())
                .user(promptTemplate.userPrompt(question, contextJson))
                .call()
                .content();
    }
}
