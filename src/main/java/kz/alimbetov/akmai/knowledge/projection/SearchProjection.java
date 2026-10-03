package kz.alimbetov.akmai.knowledge.projection;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;
import kz.alimbetov.akmai.knowledge.lifecycle.GenerationIdentity;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;

public record SearchProjection(
        String chunkId,
        String documentId,
        long generation,
        long accessLevel,
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
        if (generation < 0) {
            throw new IllegalArgumentException(
                    "generation must not be negative"
            );
        }
        if (accessLevel < 0) {
            throw new IllegalArgumentException(
                    "accessLevel must not be negative"
            );
        }
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
                0L,
                0L,
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

    public SearchProjection(
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
        this(
                chunkId,
                documentId,
                generation,
                0L,
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
                accessLevel,
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

    public SearchProjection withIdentity(GenerationIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        if (!documentId.equals(identity.documentId())) {
            throw new IllegalArgumentException(
                    "Projection document does not match generation identity"
            );
        }
        return new SearchProjection(
                chunkId,
                documentId,
                identity.generation(),
                identity.accessLevel(),
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
