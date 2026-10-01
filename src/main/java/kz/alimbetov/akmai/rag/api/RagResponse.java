package kz.alimbetov.akmai.rag.api;

import java.util.List;

public record RagResponse(
        String answer,
        List<Source> sources
) {
    public record Source(
            String source,
            String language,
            String sectionPath
    ) {
    }
}
