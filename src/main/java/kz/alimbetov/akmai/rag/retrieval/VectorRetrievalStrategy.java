package kz.alimbetov.akmai.rag.retrieval;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

@Component
public class VectorRetrievalStrategy implements RetrievalStrategy {

    private final VectorStore vectorStore;

    public VectorRetrievalStrategy(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public RetrievalType type() {
        return RetrievalType.VECTOR;
    }

    @Override
    public boolean supports(QueryChunk queryChunk) {
        return !queryChunk.semanticText().isBlank();
    }

    @Override
    public List<RetrievalHit> retrieve(QueryChunk queryChunk) {
        var documents = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(queryChunk.semanticText())
                        .topK(5)
                        .similarityThreshold(0.65)
                        .build()
        );

        if (documents == null) {
            return List.of();
        }

        return documents.stream()
                .map(document -> new RetrievalHit(
                        RetrievalType.VECTOR,
                        Objects.toString(document.getMetadata().get("documentId"), ""),
                        Objects.toString(document.getMetadata().get("chunkId"), ""),
                        document.getText(),
                        new HashMap<>(document.getMetadata())
                ))
                .toList();
    }
}
