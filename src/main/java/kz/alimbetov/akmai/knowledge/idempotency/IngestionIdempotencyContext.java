package kz.alimbetov.akmai.knowledge.idempotency;

import java.util.UUID;

public record IngestionIdempotencyContext(
        String key,
        UUID claimId,
        String fingerprint
) {
}
