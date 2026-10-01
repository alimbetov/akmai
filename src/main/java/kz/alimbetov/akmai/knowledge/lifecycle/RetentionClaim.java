package kz.alimbetov.akmai.knowledge.lifecycle;

import java.time.Instant;

public record RetentionClaim(
        String documentId,
        long generation,
        String workerId,
        Instant leaseUntil
) {
}
