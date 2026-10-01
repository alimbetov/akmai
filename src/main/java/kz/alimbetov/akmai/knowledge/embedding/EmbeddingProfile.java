package kz.alimbetov.akmai.knowledge.embedding;

import java.time.Instant;

public record EmbeddingProfile(
        String profileId,
        String provider,
        String model,
        int dimensions,
        String distanceType,
        String tokenizerProfile,
        String configFingerprint,
        String vectorSchema,
        String vectorTable,
        String indexType,
        short storageSchemaVersion,
        Instant createdAt
) {
    public EmbeddingProfile {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        if (!"COSINE_DISTANCE".equals(distanceType)) {
            throw new IllegalArgumentException("only COSINE_DISTANCE is supported");
        }
    }
}
