package kz.alimbetov.akmai.knowledge.lifecycle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class DocumentGenerationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public DocumentGenerationRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public long allocate(
            String documentId,
            RetentionPolicy requestedPolicy,
            Instant requestedExpiresAt,
            String embeddingProfileId,
            String contentFingerprint,
            long accessLevel
    ) {
        if (accessLevel <= 0) {
            throw new IllegalArgumentException("accessLevel must be positive");
        }
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
                throw new IllegalStateException(
                        "Embedding migration blocks ordinary ingestion"
                );
            }

            jdbcTemplate.update(
                    """
                    INSERT INTO knowledge_document_lifecycle (
                        document_id, lifecycle_policy, lifecycle_status,
                        generation, claim_generation, claim_id, claimed_by,
                        claimed_at, lease_until, expires_at, delete_started_at,
                        deleted_at, attempt_count, last_error, row_version,
                        ingestion_started_at, created_at, updated_at,
                        retention_status, published_generation, next_generation,
                        access_level
                    ) VALUES (
                        ?, ?, 'INGESTING',
                        1, NULL, NULL, NULL,
                        NULL, NULL, ?, NULL,
                        NULL, 0, NULL, 0,
                        clock_timestamp(), clock_timestamp(), clock_timestamp(),
                        'ACTIVE', NULL, 1, ?
                    )
                    ON CONFLICT (document_id) DO NOTHING
                    """,
                    documentId,
                    requestedPolicy.name(),
                    timestamp(requestedExpiresAt),
                    accessLevel
            );

            AllocationState current = jdbcTemplate.queryForObject(
                    """
                    SELECT next_generation, retention_status
                    FROM knowledge_document_lifecycle
                    WHERE document_id = ?
                    FOR UPDATE
                    """,
                    (rs, rowNum) -> new AllocationState(
                            rs.getLong("next_generation"),
                            RetentionStatus.valueOf(rs.getString("retention_status"))
                    ),
                    documentId
            );
            if (current == null) {
                throw new IllegalStateException("Lifecycle row disappeared");
            }
            if (current.retentionStatus() == RetentionStatus.DELETING) {
                throw new IllegalStateException(
                        "Document is currently being deleted: " + documentId
                );
            }

            long generation = current.nextGeneration();
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_document_lifecycle
                    SET next_generation = ?,
                        generation = ?,
                        lifecycle_status = 'INGESTING',
                        ingestion_started_at = clock_timestamp(),
                        retention_status = 'ACTIVE',
                        claim_generation = NULL,
                        claim_id = NULL,
                        claimed_by = NULL,
                        claimed_at = NULL,
                        lease_until = NULL,
                        delete_started_at = NULL,
                        deleted_at = NULL,
                        row_version = row_version + 1,
                        updated_at = clock_timestamp()
                    WHERE document_id = ?
                    """,
                    generation + 1,
                    generation,
                    documentId
            );

            jdbcTemplate.update(
                    """
                    INSERT INTO knowledge_document_generation (
                        document_id, generation, generation_status,
                        generation_kind, migration_id, embedding_profile_id,
                        content_fingerprint, physical_id_version,
                        cleanup_required, started_at, access_level
                    ) VALUES (?, ?, 'STAGING', 'INGESTION', NULL, ?, ?, 2, false,
                              clock_timestamp(), ?)
                    """,
                    documentId,
                    generation,
                    embeddingProfileId,
                    contentFingerprint,
                    accessLevel
            );
            return generation;
        });
    }

    public Optional<DocumentGeneration> find(String documentId, long generation) {
        return jdbcTemplate.query(
                """
                SELECT *
                FROM knowledge_document_generation
                WHERE document_id = ?
                  AND generation = ?
                """,
                this::map,
                documentId,
                generation
        ).stream().findFirst();
    }

    public boolean fail(
            String documentId,
            long generation,
            String failureCode,
            String error
    ) {
        int changed = jdbcTemplate.update(
                """
                UPDATE knowledge_document_generation
                SET generation_status = 'FAILED',
                    failure_code = ?,
                    last_error = ?,
                    failed_at = clock_timestamp()
                WHERE document_id = ?
                  AND generation = ?
                  AND generation_status = 'STAGING'
                """,
                failureCode,
                sanitize(error),
                documentId,
                generation
        );
        if (changed == 1) {
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_document_lifecycle
                    SET lifecycle_status = CASE
                            WHEN published_generation IS NULL
                                THEN 'INGEST_FAILED'
                            ELSE 'READY'
                        END,
                        ingestion_started_at = NULL,
                        last_error = ?,
                        row_version = row_version + 1,
                        updated_at = clock_timestamp()
                    WHERE document_id = ?
                      AND generation = ?
                    """,
                    sanitize(error),
                    documentId,
                    generation
            );
        }
        return changed == 1;
    }

    public int failStaleIngestionBatch(Duration staleAfter, int limit) {
        if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        List<GenerationKey> failed = jdbcTemplate.query(
                """
                WITH candidates AS (
                    SELECT g.document_id, g.generation
                    FROM knowledge_document_generation g
                    WHERE g.generation_kind = 'INGESTION'
                      AND g.generation_status = 'STAGING'
                      AND g.started_at < clock_timestamp()
                          - (? * interval '1 millisecond')
                      AND NOT EXISTS (
                          SELECT 1
                          FROM knowledge_ingestion_request r
                          WHERE r.document_id = g.document_id
                            AND r.generation = g.generation
                            AND r.request_status = 'IN_PROGRESS'
                            AND r.lease_until > clock_timestamp()
                      )
                    ORDER BY g.started_at, g.document_id, g.generation
                    FOR UPDATE OF g SKIP LOCKED
                    LIMIT ?
                )
                UPDATE knowledge_document_generation g
                SET generation_status = 'FAILED',
                    failure_code = 'STALE_INGESTION',
                    last_error = 'stale ingestion recovered',
                    cleanup_required = true,
                    failed_at = clock_timestamp()
                FROM candidates c
                WHERE g.document_id = c.document_id
                  AND g.generation = c.generation
                RETURNING g.document_id, g.generation
                """,
                (rs, rowNum) -> new GenerationKey(
                        rs.getString("document_id"),
                        rs.getLong("generation")
                ),
                staleAfter.toMillis(),
                limit
        );
        for (GenerationKey key : failed) {
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_document_lifecycle
                    SET lifecycle_status = CASE
                            WHEN published_generation IS NULL
                                THEN 'INGEST_FAILED'
                            ELSE 'READY'
                        END,
                        ingestion_started_at = NULL,
                        row_version = row_version + 1,
                        updated_at = clock_timestamp()
                    WHERE document_id = ?
                      AND generation = ?
                    """,
                    key.documentId(),
                    key.generation()
            );
        }
        return failed.size();
    }

    private DocumentGeneration map(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentGeneration(
                rs.getString("document_id"),
                rs.getLong("generation"),
                GenerationStatus.valueOf(rs.getString("generation_status")),
                GenerationKind.valueOf(rs.getString("generation_kind")),
                rs.getObject("migration_id", java.util.UUID.class),
                rs.getString("embedding_profile_id"),
                rs.getString("content_fingerprint"),
                rs.getShort("physical_id_version"),
                rs.getString("failure_code"),
                rs.getString("last_error"),
                rs.getBoolean("cleanup_required"),
                instant(rs, "started_at"),
                instant(rs, "published_at"),
                instant(rs, "failed_at"),
                instant(rs, "retired_at"),
                instant(rs, "cleaned_at")
        );
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }

    private String sanitize(String error) {
        if (error == null || error.isBlank()) {
            return "generation failed";
        }
        String value = error.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private record AllocationState(
            long nextGeneration,
            RetentionStatus retentionStatus
    ) {
    }

    private record GenerationKey(String documentId, long generation) {
    }
}
