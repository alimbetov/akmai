package kz.alimbetov.akmai.rag.query;

import java.util.List;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;

public record QueryChunk(
        String id,
        int index,
        String rawText,
        String normalizedText,
        String semanticText,
        List<DetectedIdentifier> identifiers
) {
}
