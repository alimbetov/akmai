package kz.alimbetov.akmai.knowledge.lifecycle;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PostgresDocumentLifecycleRepository
        implements DocumentLifecycleRepository {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public PostgresDocumentLifecycleRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public long activate(
            String documentId,
            RetentionPolicy policy,
            Instant expiresAt
    ) {
        validatePolicy(policy, expiresAt);
        Long generation = jdbcTemplate.queryForObject(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id, lifecycle_policy, lifecycle_status,
                    generation, claim_generation, expires_at,
                    delete_started_at, deleted_at, attempt_count,
                    last_error, row_version, created_at, updated_at
                ) VALUES (?, ?, 'READY', 1, NULL, ?, NULL, NULL, 0, NULL, 0, now(), now())
                ON CONFLICT (document_id) DO UPDATE SET
                    lifecycle_policy = EXCLUDED.lifecycle_policy,
                    lifecycle_status = 'READY',
                    generation = knowledge_document_lifecycle.generation + 1,
                    claim_generation = NULL,
                    expires_at = EXCLUDED.expires_at,
                    delete_started_at = NULL,
                    deleted_at = NULL,
                    attempt_count = 0,
                    last_error = NULL,
                    row_version = knowledge_document_lifecycle.row_version + 1,
                    updated_at = now()
                RETURNING generation
                """,
                Long.class,
                documentId,
                policy.name(),
                timestamp(expiresAt)
        );
        if (generation == null) {
            throw new IllegalStateException("Lifecycle activation returned no generation");
        }
        return generation;
    }

    @Override
    public List<RetentionClaim> claimExpired(
            Instant now,
            int batchSize,
            int retryLimit
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0");
        }
        if (retryLimit <= 0) {
            throw new IllegalArgumentException("retryLimit must be > 0");
        }

        return transactionTemplate.execute(status -> jdbcTemplate.query(
                """
                WITH candidates AS (
                    SELECT document_id
                    FROM knowledge_document_lifecycle
                    WHERE lifecycle_policy = 'TTL'
                      AND lifecycle_status IN ('READY', 'DELETE_FAILED')
                      AND expires_at <= ?
                      AND attempt_count < ?
                    ORDER BY expires_at, document_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE knowledge_document_lifecycle lifecycle
                SET lifecycle_status = 'DELETE_PENDING',
                    claim_generation = lifecycle.generation,
                    row_version = lifecycle.row_version + 1,
                    updated_at = now()
                FROM candidates
                WHERE lifecycle.document_id = candidates.document_id
                RETURNING lifecycle.document_id, lifecycle.claim_generation
                """,
                (rs, rowNum) -> new RetentionClaim(
                        rs.getString("document_id"),
                        rs.getLong("claim_generation")
                ),
                timestamp(now),
                retryLimit,
                batchSize
        ));
    }

    @Override
    public boolean markDeleting(RetentionClaim claim, Instant now) {
        return updateClaimState(
                claim,
                LifecycleStatus.DELETE_PENDING,
                LifecycleStatus.DELETING,
                now,
                null
        ) == 1;
    }

    @Override
    public boolean markDeleted(RetentionClaim claim, Instant now) {
        int updated = jdbcTemplate.update(
                """
                UPDATE knowledge_document_lifecycle
                SET lifecycle_status = 'DELETED',
                    deleted_at = ?,
                    claim_generation = NULL,
                    last_error = NULL,
                    row_version = row_version + 1,
                    updated_at = ?
                WHERE document_id = ?
                  AND generation = ?
                  AND claim_generation = ?
                  AND lifecycle_status = 'DELETING'
                """,
                timestamp(now),
                timestamp(now),
                claim.documentId(),
                claim.generation(),
                claim.generation()
        );
        return updated == 1;
    }

    @Override
    public boolean markFailed(
            RetentionClaim claim,
            Instant now,
            String error
    ) {
        String sanitized = sanitizeError(error);
        int updated = jdbcTemplate.update(
                """
                UPDATE knowledge_document_lifecycle
                SET lifecycle_status = 'DELETE_FAILED',
                    attempt_count = attempt_count + 1,
                    last_error = ?,
                    claim_generation = NULL,
                    row_version = row_version + 1,
                    updated_at = ?
                WHERE document_id = ?
                  AND generation = ?
                  AND claim_generation = ?
                  AND lifecycle_status IN ('DELETE_PENDING', 'DELETING')
                """,
                sanitized,
                timestamp(now),
                claim.documentId(),
                claim.generation(),
                claim.generation()
        );
        return updated == 1;
    }

    @Override
    public boolean isCurrentClaim(RetentionClaim claim) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                  AND generation = ?
                  AND claim_generation = ?
                  AND lifecycle_status IN ('DELETE_PENDING', 'DELETING')
                """,
                Integer.class,
                claim.documentId(),
                claim.generation(),
                claim.generation()
        );
        return count != null && count == 1;
    }

    @Override
    public Optional<DocumentLifecycle> findByDocumentId(String documentId) {
        return jdbcTemplate.query(
                """
                SELECT *
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                """,
                this::map,
                documentId
        ).stream().findFirst();
    }

    private int updateClaimState(
            RetentionClaim claim,
            LifecycleStatus expected,
            LifecycleStatus target,
            Instant now,
            String error
    ) {
        return jdbcTemplate.update(
                """
                UPDATE knowledge_document_lifecycle
                SET lifecycle_status = ?,
                    delete_started_at = CASE
                        WHEN ? = 'DELETING' THEN ?
                        ELSE delete_started_at
                    END,
                    last_error = ?,
                    row_version = row_version + 1,
                    updated_at = ?
                WHERE document_id = ?
                  AND generation = ?
                  AND claim_generation = ?
                  AND lifecycle_status = ?
                """,
                target.name(),
                target.name(),
                timestamp(now),
                error,
                timestamp(now),
                claim.documentId(),
                claim.generation(),
                claim.generation(),
                expected.name()
        );
    }

    private DocumentLifecycle map(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentLifecycle(
                rs.getString("document_id"),
                RetentionPolicy.valueOf(rs.getString("lifecycle_policy")),
                LifecycleStatus.valueOf(rs.getString("lifecycle_status")),
                rs.getLong("generation"),
                nullableLong(rs, "claim_generation"),
                instant(rs, "expires_at"),
                instant(rs, "delete_started_at"),
                instant(rs, "deleted_at"),
                rs.getInt("attempt_count"),
                rs.getString("last_error"),
                rs.getLong("row_version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void validatePolicy(RetentionPolicy policy, Instant expiresAt) {
        if (policy == RetentionPolicy.PERMANENT && expiresAt != null) {
            throw new IllegalArgumentException("PERMANENT lifecycle cannot have expiresAt");
        }
        if (policy == RetentionPolicy.TTL && expiresAt == null) {
            throw new IllegalArgumentException("TTL lifecycle requires expiresAt");
        }
    }

    private String sanitizeError(String error) {
        if (error == null || error.isBlank()) {
            return "retention cleanup failed";
        }
        String normalized = error.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= MAX_ERROR_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ERROR_LENGTH);
    }
}
