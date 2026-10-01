package kz.alimbetov.akmai.knowledge.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DocumentLifecycleRepository {

    long activate(
            String documentId,
            RetentionPolicy policy,
            Instant expiresAt
    );

    List<RetentionClaim> claimExpired(
            Instant now,
            int batchSize,
            int retryLimit
    );

    boolean markDeleting(RetentionClaim claim, Instant now);

    boolean markDeleted(RetentionClaim claim, Instant now);

    boolean markFailed(
            RetentionClaim claim,
            Instant now,
            String error
    );

    boolean isCurrentClaim(RetentionClaim claim);

    Optional<DocumentLifecycle> findByDocumentId(String documentId);
}
