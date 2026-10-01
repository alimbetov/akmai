package kz.alimbetov.akmai.knowledge.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DocumentLifecycleRepository {

    long beginIngestion(String documentId, RetentionPolicy policy, Instant expiresAt);

    boolean publishIngestion(String documentId, long generation, Instant now);

    boolean failIngestion(String documentId, long generation, Instant now, String error);

    List<String> findStaleIngestionDocumentIds(Instant staleBefore, int limit);

    boolean failStaleIngestion(String documentId, Instant staleBefore, Instant now, String error);


    List<RetentionClaim> claimExpired(
            Instant now,
            int batchSize,
            int retryLimit,
            String workerId,
            Duration leaseDuration
    );

    boolean markDeleting(RetentionClaim claim, Instant now);

    boolean renewLease(RetentionClaim claim, Instant now, Duration leaseDuration);

    boolean releaseClaim(RetentionClaim claim, Instant now);

    boolean markDeleted(RetentionClaim claim, Instant now);

    boolean markFailed(RetentionClaim claim, Instant now, String error);

    boolean isCurrentClaim(RetentionClaim claim, Instant now);

    Optional<DocumentLifecycle> findByDocumentId(String documentId);
}
