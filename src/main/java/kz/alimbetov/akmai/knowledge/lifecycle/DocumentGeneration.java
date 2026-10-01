package kz.alimbetov.akmai.knowledge.lifecycle;

import java.time.Instant;
import java.util.UUID;

public record DocumentGeneration(
        String documentId,
        long generation,
        GenerationStatus status,
        GenerationKind kind,
        UUID migrationId,
        String embeddingProfileId,
        String contentFingerprint,
        short physicalIdVersion,
        String failureCode,
        String lastError,
        boolean cleanupRequired,
        Instant startedAt,
        Instant publishedAt,
        Instant failedAt,
        Instant retiredAt,
        Instant cleanedAt
) {
}
