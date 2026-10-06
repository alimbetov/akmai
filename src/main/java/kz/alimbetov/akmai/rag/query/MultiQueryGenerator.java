package kz.alimbetov.akmai.rag.query;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class MultiQueryGenerator {

    private final ChatClient chatClient;
    private final ExecutorService executor;
    private final AdvancedRetrievalProperties properties;

    public MultiQueryGenerator(
            ChatClient.Builder builder,
            @Qualifier("queryIntelligenceExecutor") ExecutorService executor,
            AdvancedRetrievalProperties properties
    ) {
        this.chatClient = builder.build();
        this.executor = executor;
        this.properties = properties;
    }

    public List<String> generate(String question) {
        if (!properties.multiQueryEnabled()
                || question == null
                || question.isBlank()) {
            return List.of();
        }

        Future<String> future;
        try {
            future = executor.submit(() -> callModel(question));
        } catch (RejectedExecutionException exception) {
            return List.of();
        }

        try {
            String content = future.get(
                    properties.modelTimeout().toNanos(),
                    TimeUnit.NANOSECONDS
            );
            return parse(content, question);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return List.of();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException | RuntimeException exception) {
            future.cancel(true);
            return List.of();
        }
    }

    private String callModel(String question) {
        int variants = properties.multiQueryVariants();
        String prompt = """
                Rewrite the search question into %d alternative retrieval queries.
                Preserve the original meaning, entities, numbers, legal/medical terms and language.
                Do not answer the question. Do not add facts.
                Return exactly one alternative query per line, without numbering or bullets.

                QUESTION:
                %s
                """.formatted(variants, question);
        return chatClient.prompt()
                .system("You generate conservative search-query paraphrases for a RAG retriever.")
                .user(prompt)
                .call()
                .content();
    }

    private List<String> parse(String content, String original) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String normalizedOriginal = normalize(original);
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        for (String line : content.split("\\R")) {
            String candidate = clean(line);
            if (candidate.isBlank()) {
                continue;
            }
            if (normalize(candidate).equals(normalizedOriginal)) {
                continue;
            }
            variants.add(candidate);
            if (variants.size() >= properties.multiQueryVariants()) {
                break;
            }
        }
        return List.copyOf(variants);
    }

    private String clean(String raw) {
        String value = raw == null ? "" : raw.trim();
        value = value.replaceFirst("^[\\-•*]+\\s*", "");
        value = value.replaceFirst("^\\d+[.)]\\s*", "");
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }
}
