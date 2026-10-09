package kz.alimbetov.akmai.knowledge.ingestion.async;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.idempotency.IdempotencyConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class AsyncIngestionJobRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public AsyncIngestionJobRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public AsyncIngestionJob admit(AsyncIngestionJob candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("async ingestion candidate is required");
        }
        return transactionTemplate.execute(status -> {
            int inserted = jdbcTemplate.update(
                    """
                    INSERT INTO knowledge_ingestion_job (
                        ingestion_id, schema_version,
                        event_id, request_id, job_fingerprint,
                        internal_idempotency_key,
                        document_id, access_level, source_type,
                        file_id, source_version, content_hash, canonical_hash,
                        payload_mode, payload_json, artifact_id,
                        job_status, attempt_count, failure_count,
                        lease_version,
                        accepted_at, created_at, updated_at
                    ) VALUES (
                        ?, ?,
                        ?, ?, ?,
                        ?,
                        ?, ?, ?,
                        ?, ?, ?, ?,
                        ?, ?::jsonb, ?,
                        'ACCEPTED', 0, 0,
                        0,
                        clock_timestamp(), clock_timestamp(), clock_timestamp()
                    )
                    ON CONFLICT DO NOTHING
                    """,
                    candidate.ingestionId(),
                    candidate.schemaVersion(),
                    candidate.eventId(),
                    candidate.requestId(),
                    candidate.jobFingerprint(),
                    candidate.internalIdempotencyKey(),
                    candidate.documentId(),
                    candidate.accessLevel(),
                    candidate.sourceType(),
                    candidate.fileId(),
                    candidate.sourceVersion(),
                    candidate.contentHash(),
                    candidate.canonicalHash(),
                    candidate.payloadMode(),
                    candidate.payloadJson(),
                    candidate.artifactId()
            );
            if (inserted == 1) {
                return findRequired(candidate.ingestionId());
            }

            Optional<AsyncIngestionJob> byEvent = findByEventId(candidate.eventId());
            if (byEvent.isPresent()) {
                AsyncIngestionJob existing = byEvent.get();
                if (!existing.jobFingerprint().equals(candidate.jobFingerprint())) {
                    throw new IdempotencyConflictException(
                            "ASYNC_INGESTION_EVENT_REUSE",
                            "eventId was already used for a different ingestion command"
                    );
                }
                return existing;
            }

            return findByFingerprint(candidate.jobFingerprint()).orElseThrow(() ->
                    new IllegalStateException(
                            "Async ingestion admission conflict has no durable owner"
                    ));
        });
    }

    public Optional<AsyncIngestionJob> find(UUID ingestionId) {
        if (ingestionId == null) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                """
                SELECT *
                FROM knowledge_ingestion_job
                WHERE ingestion_id = ?
                """,
                this::map,
                ingestionId
        ).stream().findFirst();
    }

    public Optional<AsyncIngestionJob> findByEventId(String eventId) {
        return jdbcTemplate.query(
                """
                SELECT *
                FROM knowledge_ingestion_job
                WHERE event_id = ?
                """,
                this::map,
                eventId
        ).stream().findFirst();
    }

    public Optional<AsyncIngestionJob> findByFingerprint(String fingerprint) {
        return jdbcTemplate.query(
                """
                SELECT *
                FROM knowledge_ingestion_job
                WHERE job_fingerprint = ?
                ORDER BY accepted_at DESC
                LIMIT 1
                """,
                this::map,
                fingerprint
        ).stream().findFirst();
    }

    public List<AsyncIngestionClaim> claimEligible(
            String leaseOwner,
            int limit,
            Duration leaseDuration
    ) {
        requireLeaseOwner(leaseOwner);
        if (limit <= 0) {
            throw new IllegalArgumentException("claim limit must be > 0");
        }
        requirePositive(leaseDuration, "leaseDuration");

        return transactionTemplate.execute(status -> jdbcTemplate.query(
                """
                WITH candidates AS (
                    SELECT ingestion_id
                    FROM knowledge_ingestion_job
                    WHERE (
                        job_status = 'ACCEPTED'
                        OR (
                            job_status = 'RETRY_WAIT'
                            AND next_attempt_at <= clock_timestamp()
                        )
                        OR (
                            job_status = 'PROCESSING'
                            AND lease_until < clock_timestamp()
                        )
                    )
                    ORDER BY accepted_at, ingestion_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE knowledge_ingestion_job j
                SET job_status = 'PROCESSING',
                    lease_owner = ?,
                    lease_until = clock_timestamp()
                        + (? * interval '1 millisecond'),
                    lease_version = j.lease_version + 1,
                    attempt_count = j.attempt_count + 1,
                    next_attempt_at = NULL,
                    started_at = COALESCE(j.started_at, clock_timestamp()),
                    updated_at = clock_timestamp()
                FROM candidates c
                WHERE j.ingestion_id = c.ingestion_id
                RETURNING j.*
                """,
                (rs, rowNum) -> {
                    AsyncIngestionJob job = map(rs, rowNum);
                    return new AsyncIngestionClaim(
                            job.ingestionId(),
                            job.leaseOwner(),
                            job.leaseVersion(),
                            job
                    );
                },
                limit,
                leaseOwner,
                leaseDuration.toMillis()
        ));
    }

    public boolean renew(
            AsyncIngestionClaim claim,
            Duration leaseDuration
    ) {
        requireClaim(claim);
        requirePositive(leaseDuration, "leaseDuration");
        return jdbcTemplate.update(
                """
                UPDATE knowledge_ingestion_job
                SET lease_until = clock_timestamp()
                    + (? * interval '1 millisecond'),
                    updated_at = clock_timestamp()
                WHERE ingestion_id = ?
                  AND job_status = 'PROCESSING'
                  AND lease_owner = ?
                  AND lease_version = ?
                  AND lease_until > clock_timestamp()
                """,
                leaseDuration.toMillis(),
                claim.ingestionId(),
                claim.leaseOwner(),
                claim.leaseVersion()
        ) == 1;
    }

    public boolean markRetry(
            AsyncIngestionClaim claim,
            Instant nextAttemptAt,
            boolean consumeFailureBudget,
            String errorClass,
            String errorCode,
            String errorMessage
    ) {
        requireClaim(claim);
        if (nextAttemptAt == null) {
            throw new IllegalArgumentException("nextAttemptAt is required");
        }
        return jdbcTemplate.update(
                """
                UPDATE knowledge_ingestion_job
                SET job_status = 'RETRY_WAIT',
                    failure_count = failure_count + ?,
                    next_attempt_at = ?,
                    lease_owner = NULL,
                    lease_until = NULL,
                    last_error_class = ?,
                    last_error_code = ?,
                    last_error_message = ?,
                    updated_at = clock_timestamp()
                WHERE ingestion_id = ?
                  AND job_status = 'PROCESSING'
                  AND lease_owner = ?
                  AND lease_version = ?
                  AND lease_until > clock_timestamp()
                """,
                consumeFailureBudget ? 1 : 0,
                java.sql.Timestamp.from(nextAttemptAt),
                errorClass,
                errorCode,
                sanitize(errorMessage),
                claim.ingestionId(),
                claim.leaseOwner(),
                claim.leaseVersion()
        ) == 1;
    }

    public boolean markIngested(
            AsyncIngestionClaim claim,
            long generation,
            int chunkCount,
            String embeddingProfileId
    ) {
        requireClaim(claim);
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount must be non-negative");
        }
        return jdbcTemplate.update(
                """
                UPDATE knowledge_ingestion_job
                SET job_status = 'INGESTED',
                    generation = ?,
                    chunk_count = ?,
                    embedding_profile_id = ?,
                    next_attempt_at = NULL,
                    lease_owner = NULL,
                    lease_until = NULL,
                    last_error_class = NULL,
                    last_error_code = NULL,
                    last_error_message = NULL,
                    finished_at = clock_timestamp(),
                    updated_at = clock_timestamp()
                WHERE ingestion_id = ?
                  AND job_status = 'PROCESSING'
                  AND lease_owner = ?
                  AND lease_version = ?
                  AND lease_until > clock_timestamp()
                """,
                generation,
                chunkCount,
                embeddingProfileId,
                claim.ingestionId(),
                claim.leaseOwner(),
                claim.leaseVersion()
        ) == 1;
    }

    public boolean markFailed(
            AsyncIngestionClaim claim,
            String errorClass,
            String errorCode,
            String errorMessage
    ) {
        requireClaim(claim);
        return jdbcTemplate.update(
                """
                UPDATE knowledge_ingestion_job
                SET job_status = 'FAILED',
                    failure_count = failure_count + 1,
                    next_attempt_at = NULL,
                    lease_owner = NULL,
                    lease_until = NULL,
                    last_error_class = ?,
                    last_error_code = ?,
                    last_error_message = ?,
                    finished_at = clock_timestamp(),
                    updated_at = clock_timestamp()
                WHERE ingestion_id = ?
                  AND job_status = 'PROCESSING'
                  AND lease_owner = ?
                  AND lease_version = ?
                  AND lease_until > clock_timestamp()
                """,
                errorClass,
                errorCode,
                sanitize(errorMessage),
                claim.ingestionId(),
                claim.leaseOwner(),
                claim.leaseVersion()
        ) == 1;
    }

    public long countBacklog() {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_ingestion_job
                WHERE job_status IN ('ACCEPTED', 'RETRY_WAIT', 'PROCESSING')
                """,
                Long.class
        );
        return count == null ? 0L : count;
    }

    private AsyncIngestionJob findRequired(UUID ingestionId) {
        return find(ingestionId).orElseThrow(() ->
                new IllegalStateException("Async ingestion job disappeared after insert"));
    }

    private AsyncIngestionJob map(ResultSet rs, int rowNum) throws SQLException {
        Number generationValue = (Number) rs.getObject("generation");
        Number chunkCountValue = (Number) rs.getObject("chunk_count");
        Long generation = generationValue == null
                ? null
                : generationValue.longValue();
        Integer chunkCount = chunkCountValue == null
                ? null
                : chunkCountValue.intValue();
        return new AsyncIngestionJob(
                rs.getObject("ingestion_id", UUID.class),
                rs.getInt("schema_version"),
                rs.getString("event_id"),
                rs.getString("request_id"),
                rs.getString("job_fingerprint"),
                rs.getString("internal_idempotency_key"),
                rs.getString("document_id"),
                rs.getLong("access_level"),
                rs.getString("source_type"),
                rs.getString("file_id"),
                rs.getString("source_version"),
                rs.getString("content_hash"),
                rs.getString("canonical_hash"),
                rs.getString("payload_mode"),
                rs.getString("payload_json"),
                rs.getString("artifact_id"),
                AsyncIngestionJobStatus.valueOf(rs.getString("job_status")),
                rs.getInt("attempt_count"),
                rs.getInt("failure_count"),
                instant(rs, "next_attempt_at"),
                rs.getString("lease_owner"),
                instant(rs, "lease_until"),
                rs.getLong("lease_version"),
                generation,
                chunkCount,
                rs.getString("embedding_profile_id"),
                rs.getString("last_error_class"),
                rs.getString("last_error_code"),
                rs.getString("last_error_message"),
                instant(rs, "accepted_at"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private void requireClaim(AsyncIngestionClaim claim) {
        if (claim == null
                || claim.ingestionId() == null
                || claim.leaseOwner() == null
                || claim.leaseOwner().isBlank()
                || claim.leaseVersion() <= 0) {
            throw new IllegalArgumentException("valid async ingestion claim is required");
        }
    }

    private void requireLeaseOwner(String leaseOwner) {
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner is required");
        }
    }

    private void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "async ingestion failed";
        }
        String clean = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return clean.length() <= 1000 ? clean : clean.substring(0, 1000);
    }
}
