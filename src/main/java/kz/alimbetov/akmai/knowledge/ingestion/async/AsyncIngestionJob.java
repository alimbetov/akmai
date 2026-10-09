package kz.alimbetov.akmai.knowledge.ingestion.async;

import java.time.Instant;
import java.util.UUID;

public record AsyncIngestionJob(
        UUID ingestionId,
        int schemaVersion,
        String eventId,
        String requestId,
        String jobFingerprint,
        String internalIdempotencyKey,
        String documentId,
        long accessLevel,
        String sourceType,
        String fileId,
        String sourceVersion,
        String contentHash,
        String canonicalHash,
        String payloadMode,
        String payloadJson,
        String artifactId,
        AsyncIngestionJobStatus status,
        int attemptCount,
        int failureCount,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseUntil,
        long leaseVersion,
        Long generation,
        Integer chunkCount,
        String embeddingProfileId,
        String lastErrorClass,
        String lastErrorCode,
        String lastErrorMessage,
        Instant acceptedAt,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
