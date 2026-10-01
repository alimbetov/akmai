package kz.alimbetov.akmai.knowledge.embedding;

import java.time.Instant;

public record EmbeddingRuntime(
        String activeProfileId,
        String migrationProfileId,
        MigrationStatus migrationStatus,
        long rowVersion,
        Instant updatedAt
) {
    public enum MigrationStatus {
        IDLE,
        PREPARING,
        STAGING,
        READY_TO_CUTOVER
    }
}
