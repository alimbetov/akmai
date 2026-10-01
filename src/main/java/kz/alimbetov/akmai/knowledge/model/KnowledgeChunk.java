package kz.alimbetov.akmai.knowledge.model;

import java.util.List;
import java.util.Map;

public record KnowledgeChunk(
        String chunkId,
        String documentId,
        String parentChunkId,
        int chunkIndex,
        String rawText,
        String normalizedText,
        String embeddingText,
        String title,
        String sectionPath,
        String language,
        KnowledgeDomain domain,
        List<String> references,
        Map<String, Object> metadata
) {
}
