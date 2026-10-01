package kz.alimbetov.akmai.knowledge.identifier;

import java.time.Instant;

public record DocumentIdentifier(
        String documentId,
        long generation,
        String chunkId,
        int pageNumber,
        IdentifierType type,
        String rawValue,
        String normalizedValue,
        String contextText,
        Instant createdAt
) {
    public DocumentIdentifier(
            String documentId,
            String chunkId,
            int pageNumber,
            IdentifierType type,
            String rawValue,
            String normalizedValue,
            String contextText,
            Instant createdAt
    ) {
        this(
                documentId,
                1L,
                chunkId,
                pageNumber,
                type,
                rawValue,
                normalizedValue,
                contextText,
                createdAt
        );
    }

    public DocumentIdentifier withGeneration(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("generation must be > 0");
        }
        return new DocumentIdentifier(
                documentId,
                value,
                chunkId,
                pageNumber,
                type,
                rawValue,
                normalizedValue,
                contextText,
                createdAt
        );
    }
}
