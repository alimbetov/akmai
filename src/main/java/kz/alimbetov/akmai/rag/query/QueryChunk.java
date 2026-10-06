package kz.alimbetov.akmai.rag.query;

import java.util.List;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;

public record QueryChunk(
        String id,
        int index,
        String rawText,
        String normalizedText,
        String semanticText,
        String language,
        List<DetectedIdentifier> identifiers,
        QueryOrigin origin,
        String rootQuery
) {
    public QueryChunk(
            String id,
            int index,
            String rawText,
            String normalizedText,
            String semanticText,
            String language,
            List<DetectedIdentifier> identifiers
    ) {
        this(
                id,
                index,
                rawText,
                normalizedText,
                semanticText,
                language,
                identifiers,
                QueryOrigin.ORIGINAL,
                rawText
        );
    }

    public QueryChunk(
            String id,
            int index,
            String rawText,
            String normalizedText,
            String semanticText,
            String language,
            List<DetectedIdentifier> identifiers,
            QueryOrigin origin
    ) {
        this(
                id,
                index,
                rawText,
                normalizedText,
                semanticText,
                language,
                identifiers,
                origin,
                rawText
        );
    }

    public QueryChunk {
        origin = origin == null ? QueryOrigin.ORIGINAL : origin;
        identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
        rootQuery = rootQuery == null || rootQuery.isBlank()
                ? rawText
                : rootQuery.trim();
    }
}
