package kz.alimbetov.akmai.rag.api;

import java.util.List;

public record RagResponse(
        String requestId,
        String answer,
        List<Source> sources
) {
    public RagResponse(String answer, List<Source> sources) {
        this(null, answer, sources);
    }

    public RagResponse {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

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
