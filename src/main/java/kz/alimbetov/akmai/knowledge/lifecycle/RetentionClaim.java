package kz.alimbetov.akmai.knowledge.lifecycle;

import java.time.Instant;
import java.util.UUID;

public record RetentionClaim(
        String documentId,
        long generation,
        UUID claimId,
        String workerId,
        Instant leaseUntil
) {
}
