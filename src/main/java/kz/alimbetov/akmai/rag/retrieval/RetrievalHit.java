package kz.alimbetov.akmai.rag.retrieval;

import java.util.Map;

public record RetrievalHit(
        RetrievalType type,
        String documentId,
        String chunkId,
        String text,
        Map<String, Object> metadata
) {
}
