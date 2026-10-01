package kz.alimbetov.akmai.rag.api;

import java.util.List;

public record RagResponse(
        String answer,
        List<Source> sources
) {
    public record Source(
            int number,
            String documentId,
            String chunkId,
            String source,
            String language,
            String sectionPath,
            String page
    ) {
    }
}
