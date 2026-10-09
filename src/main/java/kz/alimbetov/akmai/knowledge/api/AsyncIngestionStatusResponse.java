package kz.alimbetov.akmai.knowledge.api;

import java.time.Instant;
import java.util.UUID;

public record AsyncIngestionStatusResponse(
        int schemaVersion,
        UUID ingestionId,
        String documentId,
        String status,
        int attemptCount,
        int failureCount,
        Publication publication,
        Error error,
        Instant acceptedAt,
        Instant startedAt,
        Instant finishedAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public record Publication(
            Long generation,
            Integer chunkCount,
            String embeddingProfileId
    ) {
    }

    public record Error(
            String classification,
            String code,
            String message
    ) {
    }
}
