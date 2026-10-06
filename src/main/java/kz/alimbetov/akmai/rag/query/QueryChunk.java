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
        QueryOrigin origin
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
                QueryOrigin.ORIGINAL
        );
    }

    public QueryChunk {
        origin = origin == null ? QueryOrigin.ORIGINAL : origin;
        identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
    }
}
