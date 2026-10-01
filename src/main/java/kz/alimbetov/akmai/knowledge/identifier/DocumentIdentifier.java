package kz.alimbetov.akmai.knowledge.identifier;

import java.time.Instant;

public record DocumentIdentifier(
        String documentId,
        String chunkId,
        int pageNumber,
        IdentifierType type,
        String rawValue,
        String normalizedValue,
        String contextText,
        Instant createdAt
) {
}
