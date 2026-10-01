package kz.alimbetov.akmai.rag.retrieval;

import java.util.List;
import java.util.Map;

public record RetrievalHit(
        RetrievalType type,
        String documentId,
        String chunkId,
        String text,
        Map<String, Object> metadata,
        List<RetrievalEvidence> evidence,
        double fusedScore
) {
    public RetrievalHit {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public RetrievalHit(
            RetrievalType type,
            String documentId,
            String chunkId,
            String text,
            Map<String, Object> metadata
    ) {
        this(type, documentId, chunkId, text, metadata, List.of(), 0.0);
    }
}
