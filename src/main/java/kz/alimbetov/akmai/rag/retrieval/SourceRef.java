package kz.alimbetov.akmai.rag.retrieval;

import java.util.Objects;

public record SourceRef(
        int number,
        String documentId,
        String chunkId,
        String source,
        String language,
        String sectionPath,
        String page
) {
    public static SourceRef from(int number, RetrievalHit hit) {
        return new SourceRef(
                number,
                hit.documentId(),
                hit.chunkId(),
                Objects.toString(hit.metadata().get("source"), hit.documentId()),
                Objects.toString(hit.metadata().get("language"), "unknown"),
                Objects.toString(hit.metadata().get("sectionPath"), "unknown"),
                Objects.toString(hit.metadata().get("page"), "unknown")
        );
    }
}
