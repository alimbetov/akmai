package kz.alimbetov.akmai.knowledge.projection;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;

public record SearchProjection(
        String chunkId,
        String documentId,
        String parentChunkId,
        int chunkIndex,
        String text,
        String embeddingText,
        String language,
        KnowledgeDomain domain,
        String sectionPath,
        List<DetectedIdentifier> identifiers,
        List<String> references,
        Map<String, Object> metadata,
        int projectionVersion
) {
}
