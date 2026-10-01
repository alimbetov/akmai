package kz.alimbetov.akmai.rag.retrieval;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

@Component
public class VectorRetrievalStrategy implements RetrievalStrategy {

    private final VectorStore vectorStore;
    private final RetrievalProperties properties;

    public VectorRetrievalStrategy(VectorStore vectorStore, RetrievalProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    @Override
    public RetrievalType type() {
        return RetrievalType.VECTOR;
    }

    @Override
    public List<RetrievalHit> retrieve(
            QueryChunk queryChunk,
            RetrievalContext context
    ) {
        SearchRequest.Builder request = SearchRequest.builder()
                .query(queryChunk.semanticText())
                .topK(properties.vectorTopK())
                .similarityThreshold(properties.vectorSimilarityThreshold());

        Set<String> documentIds = context.documentIds();
        if (!documentIds.isEmpty()) {
            request.filterExpression(documentFilter(documentIds));
        }

        var documents = vectorStore.similaritySearch(request.build());
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

    private String documentFilter(Set<String> documentIds) {
        return documentIds.stream()
                .map(this::quote)
                .map(id -> "documentId == " + id)
                .reduce((left, right) -> "(" + left + " || " + right + ")")
                .orElseThrow();
    }

    private String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
