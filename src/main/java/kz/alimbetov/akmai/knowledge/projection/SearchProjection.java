package kz.alimbetov.akmai.knowledge.projection;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;

public record SearchProjection(
        String chunkId,
        String documentId,
        long generation,
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
    public SearchProjection {
        identifiers = List.copyOf(identifiers == null ? List.of() : identifiers);
        references = List.copyOf(references == null ? List.of() : references);
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }

    public SearchProjection(
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
        this(
                chunkId,
                documentId,
                1L,
                parentChunkId,
                chunkIndex,
                text,
                embeddingText,
                language,
                domain,
                sectionPath,
                identifiers,
                references,
                metadata,
                projectionVersion
        );
    }

    public SearchProjection withGeneration(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("generation must be > 0");
        }
        return new SearchProjection(
                chunkId,
                documentId,
                value,
                parentChunkId,
                chunkIndex,
                text,
                embeddingText,
                language,
                domain,
                sectionPath,
                identifiers,
                references,
                metadata,
                projectionVersion
        );
    }
}
