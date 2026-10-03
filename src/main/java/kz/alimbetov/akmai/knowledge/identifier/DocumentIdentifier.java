package kz.alimbetov.akmai.knowledge.identifier;

import java.time.Instant;
import kz.alimbetov.akmai.knowledge.lifecycle.GenerationIdentity;

public record DocumentIdentifier(
        String documentId,
        long generation,
        long accessLevel,
        String chunkId,
        int pageNumber,
        IdentifierType type,
        String rawValue,
        String normalizedValue,
        String contextText,
        Instant createdAt
) {
    public DocumentIdentifier {
        if (generation <= 0) {
            throw new IllegalArgumentException(
                    "generation must be positive"
            );
        }
        if (accessLevel < 0) {
            throw new IllegalArgumentException(
                    "accessLevel must not be negative"
            );
        }
    }

    public DocumentIdentifier(
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
        this(
                documentId,
                generation,
                0L,
                chunkId,
                pageNumber,
                type,
                rawValue,
                normalizedValue,
                contextText,
                createdAt
        );
    }

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
                0L,
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
            throw new IllegalArgumentException(
                    "generation must be positive"
            );
        }
        return new DocumentIdentifier(
                documentId,
                value,
                accessLevel,
                chunkId,
                pageNumber,
                type,
                rawValue,
                normalizedValue,
                contextText,
                createdAt
        );
    }

    public DocumentIdentifier withIdentity(GenerationIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        if (!documentId.equals(identity.documentId())) {
            throw new IllegalArgumentException(
                    "Identifier document does not match generation identity"
            );
        }
        return new DocumentIdentifier(
                documentId,
                identity.generation(),
                identity.accessLevel(),
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
