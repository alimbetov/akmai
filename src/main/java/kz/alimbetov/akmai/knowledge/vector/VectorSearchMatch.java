package kz.alimbetov.akmai.knowledge.vector;

import java.util.Map;

public record VectorSearchMatch(
        String vectorId,
        String documentId,
        long generation,
        String chunkId,
        String content,
        Map<String, Object> metadata,
        double score
) {
    public VectorSearchMatch {
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }
}
