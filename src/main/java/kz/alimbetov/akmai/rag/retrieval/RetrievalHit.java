package kz.alimbetov.akmai.rag.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.SourceProvenance;
import kz.alimbetov.akmai.knowledge.model.SourceProvenanceMetadata;

public record RetrievalHit(
        RetrievalType type,
        long accessLevel,
        String documentId,
        long generation,
        String chunkId,
        String text,
        Map<String, Object> metadata,
        List<RetrievalEvidence> evidence,
        double fusedScore,
        SourceProvenance sourceProvenance
) {
    public RetrievalHit {
        metadata = canonicalMetadata(metadata);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        sourceProvenance = sourceProvenance == null
                ? SourceProvenanceMetadata.fromMetadata(metadata)
                : sourceProvenance;
    }

    public RetrievalHit(
            RetrievalType type,
            long accessLevel,
            String documentId,
            long generation,
            String chunkId,
            String text,
            Map<String, Object> metadata,
            List<RetrievalEvidence> evidence,
            double fusedScore
    ) {
        this(
                type,
                accessLevel,
                documentId,
                generation,
                chunkId,
                text,
                metadata,
                evidence,
                fusedScore,
                null
        );
    }

    public RetrievalHit(
            RetrievalType type,
            long accessLevel,
            String documentId,
            long generation,
            String chunkId,
            String text,
            Map<String, Object> metadata
    ) {
        this(
                type,
                accessLevel,
                documentId,
                generation,
                chunkId,
                text,
                metadata,
                List.of(),
                0.0,
                null
        );
    }

    public RetrievalHit(
            RetrievalType type,
            String documentId,
            String chunkId,
            String text,
            Map<String, Object> metadata
    ) {
        this(
                type,
                number(metadata, "accessLevel"),
                documentId,
                number(metadata, "generation"),
                chunkId,
                text,
                metadata,
                List.of(),
                0.0,
                null
        );
    }

    public RetrievalHit(
            RetrievalType type,
            String documentId,
            String chunkId,
            String text,
            Map<String, Object> metadata,
            List<RetrievalEvidence> evidence,
            double fusedScore
    ) {
        this(
                type,
                number(metadata, "accessLevel"),
                documentId,
                number(metadata, "generation"),
                chunkId,
                text,
                metadata,
                evidence,
                fusedScore,
                null
        );
    }

    public boolean hasRoutingIdentity() {
        return accessLevel > 0
                && generation > 0
                && documentId != null
                && !documentId.isBlank()
                && chunkId != null
                && !chunkId.isBlank();
    }

    private static long number(
            Map<String, Object> metadata,
            String key
    ) {
        if (metadata == null) {
            return 0L;
        }
        Object value = metadata.get(key);
        return value instanceof Number number
                ? number.longValue()
                : 0L;
    }

    private static Map<String, Object> canonicalMetadata(
            Map<String, Object> input
    ) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> clean = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                clean.put(key, value);
            }
        });
        return Map.copyOf(clean);
    }
}
