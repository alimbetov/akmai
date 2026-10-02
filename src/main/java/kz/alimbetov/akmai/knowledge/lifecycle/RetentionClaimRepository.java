package kz.alimbetov.akmai.knowledge.lifecycle;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class RetentionClaimRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public RetentionClaimRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public List<RetentionClaim> claimExpired(
            int batchSize,
            int retryLimit,
            String workerId,
            Duration leaseDuration
    ) {
        if (batchSize <= 0 || retryLimit <= 0) {
            throw new IllegalArgumentException("claim limits must be positive");
        }
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        if (leaseDuration == null
                || leaseDuration.isZero()
                || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }

        UUID claimId = UUID.randomUUID();
        return transactionTemplate.execute(status -> {
            String migrationStatus = jdbcTemplate.queryForObject(
                    """
                    SELECT migration_status
                    FROM knowledge_embedding_runtime
                    WHERE singleton_id = 1
                    FOR SHARE
                    """,
                    String.class
            );
            if (!"IDLE".equals(migrationStatus)) {
                return List.of();
            }
            return jdbcTemplate.query(
                """
                WITH candidates AS (
                    SELECT l.document_id
                    FROM knowledge_document_lifecycle l
                    WHERE l.lifecycle_policy = 'TTL'
                      AND l.expires_at <= clock_timestamp()
                      AND l.published_generation IS NOT NULL
                      AND l.attempt_count < ?
                      AND NOT EXISTS (
                          SELECT 1
                          FROM knowledge_document_generation g
                          WHERE g.document_id = l.document_id
                            AND g.generation_status = 'STAGING'
                      )
                      AND (
                          l.retention_status IN ('ACTIVE', 'DELETE_FAILED')
                          OR (
                              l.retention_status IN ('DELETE_PENDING', 'DELETING')
                              AND l.lease_until < clock_timestamp()
                          )
                      )
                    ORDER BY l.expires_at, l.document_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE knowledge_document_lifecycle l
                SET retention_status = 'DELETE_PENDING',
                    lifecycle_status = 'DELETE_PENDING',
                    claim_generation = l.published_generation,
                    claim_id = ?,
                    claimed_by = ?,
                    claimed_at = clock_timestamp(),
                    lease_until = clock_timestamp()
                        + (? * interval '1 millisecond'),
                    row_version = l.row_version + 1,
                    updated_at = clock_timestamp()
                FROM candidates c
                WHERE l.document_id = c.document_id
                RETURNING l.document_id, l.claim_generation, l.claim_id,
                          l.claimed_by, l.lease_until
                """,
                (rs, rowNum) -> new RetentionClaim(
                        rs.getString("document_id"),
                        rs.getLong("claim_generation"),
                        rs.getObject("claim_id", UUID.class),
                        rs.getString("claimed_by"),
                        rs.getTimestamp("lease_until").toInstant()
                ),
                retryLimit,
                batchSize,
                claimId,
                workerId,
                leaseDuration.toMillis()
            );
        });
    }

    public long countEligibleBacklog(int retryLimit) {
        if (retryLimit <= 0) {
            throw new IllegalArgumentException("retryLimit must be positive");
        }
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_document_lifecycle l
                WHERE l.lifecycle_policy = 'TTL'
                  AND l.expires_at <= clock_timestamp()
                  AND l.published_generation IS NOT NULL
                  AND l.attempt_count < ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM knowledge_document_generation g
                      WHERE g.document_id = l.document_id
                        AND g.generation_status = 'STAGING'
                  )
                  AND (
                      l.retention_status IN ('ACTIVE', 'DELETE_FAILED')
                      OR (
                          l.retention_status IN ('DELETE_PENDING', 'DELETING')
                          AND l.lease_until < clock_timestamp()
                      )
                  )
                """,
                Long.class,
                retryLimit
        );
        return count == null ? 0L : count;
    }

    public boolean release(RetentionClaim claim) {
        return jdbcTemplate.update(
                """
                UPDATE knowledge_document_lifecycle
                SET retention_status = 'ACTIVE',
                    lifecycle_status = 'READY',
                    claim_generation = NULL,
                    claim_id = NULL,
                    claimed_by = NULL,
                    claimed_at = NULL,
                    lease_until = NULL,
                    row_version = row_version + 1,
                    updated_at = clock_timestamp()
                WHERE document_id = ?
                  AND claim_generation = ?
                  AND claim_id = ?
                  AND claimed_by = ?
                  AND retention_status = 'DELETE_PENDING'
                """,
                claim.documentId(),
                claim.generation(),
                claim.claimId(),
                claim.workerId()
        ) == 1;
    }

    public boolean markFailed(RetentionClaim claim, String error) {
        return jdbcTemplate.update(
                """
                UPDATE knowledge_document_lifecycle
                SET retention_status = 'DELETE_FAILED',
                    lifecycle_status = 'DELETE_FAILED',
                    attempt_count = attempt_count + 1,
                    last_error = ?,
                    claim_generation = NULL,
                    claim_id = NULL,
                    claimed_by = NULL,
                    claimed_at = NULL,
                    lease_until = NULL,
                    row_version = row_version + 1,
                    updated_at = clock_timestamp()
                WHERE document_id = ?
                  AND claim_generation = ?
                  AND claim_id = ?
                  AND claimed_by = ?
                  AND lease_until > clock_timestamp()
                  AND retention_status IN ('DELETE_PENDING', 'DELETING')
                """,
                sanitize(error),
                claim.documentId(),
                claim.generation(),
                claim.claimId(),
                claim.workerId()
        ) == 1;
    }

    private String sanitize(String error) {
        if (error == null || error.isBlank()) {
            return "retention cleanup failed";
        }
        String value = error.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
