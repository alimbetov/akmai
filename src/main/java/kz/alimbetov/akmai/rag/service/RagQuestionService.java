package kz.alimbetov.akmai.rag.service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import kz.alimbetov.akmai.rag.api.RagResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class RagQuestionService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public RagQuestionService(
            VectorStore vectorStore,
            ChatClient.Builder chatClientBuilder
    ) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
    }

    public RagResponse ask(String question) {
        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(5)
                        .similarityThreshold(0.65)
                        .build()
        );

        if (documents == null || documents.isEmpty()) {
            return new RagResponse(
                    "В базе знаний недостаточно информации.",
                    List.of()
            );
        }

        String context = buildContext(documents);

        String answer = chatClient
                .prompt()
                .system("""
                        Ты ассистент корпоративной базы знаний.
                        Отвечай только на основании предоставленного CONTEXT.
                        Не придумывай отсутствующие факты.
                        Если информации недостаточно, прямо сообщи об этом.
                        Отвечай на языке вопроса пользователя.
                        Для медицинских и юридических данных не скрывай условия,
                        исключения, противопоказания, ограничения и ссылки.
                        Использованные источники обозначай как [SOURCE 1], [SOURCE 2] и т.д.
                        """)
                .user("""
                        QUESTION:
                        %s

                        CONTEXT:
                        %s
                        """.formatted(question, context))
                .call()
                .content();

        List<RagResponse.Source> sources = documents.stream()
                .map(document -> new RagResponse.Source(
                        Objects.toString(document.getMetadata().get("source"), "unknown"),
                        Objects.toString(document.getMetadata().get("language"), "unknown"),
                        Objects.toString(document.getMetadata().get("sectionPath"), "unknown")
                ))
                .distinct()
                .toList();

        return new RagResponse(answer, sources);
    }

    private String buildContext(List<Document> documents) {
        return IntStream.range(0, documents.size())
                .mapToObj(index -> {
                    Document document = documents.get(index);

                    return """
                            [SOURCE %d]
                            source: %s
                            language: %s
                            section: %s
                            references: %s

                            %s
                            """.formatted(
                            index + 1,
                            document.getMetadata().get("source"),
                            document.getMetadata().get("language"),
                            document.getMetadata().get("sectionPath"),
                            document.getMetadata().get("references"),
                            document.getText()
                    );
                })
                .collect(Collectors.joining("\n\n"));
    }
}
