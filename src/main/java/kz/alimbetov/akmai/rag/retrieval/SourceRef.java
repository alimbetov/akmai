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
        Object page = hit.metadata().get("pageFrom");
        if (page == null) {
            page = hit.metadata().get("pageNumber");
        }
        if (page == null) {
            page = hit.metadata().get("page");
        }
        Object pageTo = hit.metadata().get("pageTo");
        String pageValue = page == null
                ? "unknown"
                : pageTo != null && !Objects.equals(page, pageTo)
                        ? page + "-" + pageTo
                        : Objects.toString(page);

        return new SourceRef(
                number,
                safe(hit.documentId(), "unknown"),
                safe(hit.chunkId(), "unknown"),
                safe(hit.metadata().get("source"), hit.documentId()),
                safe(hit.metadata().get("language"), "unknown"),
                safe(hit.metadata().get("sectionPath"), "unknown"),
                pageValue
        );
    }

    private static String safe(Object value, Object fallback) {
        String result = Objects.toString(value, Objects.toString(fallback, "unknown"));
        return result.isBlank() ? "unknown" : result;
    }
}
