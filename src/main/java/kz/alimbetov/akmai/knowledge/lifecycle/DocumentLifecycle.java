package kz.alimbetov.akmai.knowledge.lifecycle;

import java.time.Instant;

public record DocumentLifecycle(
        String documentId,
        RetentionPolicy policy,
        LifecycleStatus status,
        long generation,
        Long claimGeneration,
        Instant expiresAt,
        Instant deleteStartedAt,
        Instant deletedAt,
        int attemptCount,
        String lastError,
        long rowVersion,
        Instant createdAt,
        Instant updatedAt
) {
}
