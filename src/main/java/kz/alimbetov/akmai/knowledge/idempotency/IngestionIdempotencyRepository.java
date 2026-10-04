package kz.alimbetov.akmai.knowledge.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class IngestionIdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public IngestionIdempotencyRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public ClaimResult claim(
            String key,
            String documentId,
            String fingerprint,
            Duration leaseDuration
    ) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        UUID candidateClaimId = UUID.randomUUID();

        return transactionTemplate.execute(status -> {
            int inserted = jdbcTemplate.update(
                    """
                    INSERT INTO knowledge_ingestion_request (
                        idempotency_key, document_id, request_fingerprint,
                        request_status, claim_id, lease_until,
                        created_at, updated_at
                    ) VALUES (
                        ?, ?, ?, 'IN_PROGRESS', ?,
                        clock_timestamp() + (? * interval '1 millisecond'),
                        clock_timestamp(), clock_timestamp()
                    )
                    ON CONFLICT (idempotency_key) DO NOTHING
                    """,
                    key,
                    documentId,
                    fingerprint,
                    candidateClaimId,
                    leaseDuration.toMillis()
            );

            RequestRow row = selectForUpdate(key);
            if (!fingerprint.equals(row.fingerprint())) {
                throw new IdempotencyConflictException(
                        "IDEMPOTENCY_KEY_REUSE",
                        "Idempotency-Key was already used for another request"
                );
            }

            if (inserted == 1) {
                return ClaimResult.claimed(new IngestionIdempotencyContext(
                        key,
                        candidateClaimId,
                        fingerprint
                ));
            }

            if ("SUCCEEDED".equals(row.status())) {
                return ClaimResult.replay(readResponse(row.responseJson()));
            }

            Instant databaseNow = jdbcTemplate.queryForObject(
                    "SELECT clock_timestamp()",
                    java.sql.Timestamp.class
            ).toInstant();

            if ("IN_PROGRESS".equals(row.status())
                    && row.leaseUntil() != null
                    && row.leaseUntil().isAfter(databaseNow)) {
                long retryAfterSeconds = Math.max(
                        1L,
                        Duration.between(databaseNow, row.leaseUntil()).toSeconds()
                );
                return ClaimResult.inProgress(retryAfterSeconds);
            }

            if (row.generation() != null) {
                String generationStatus = jdbcTemplate.query(
                        """
                        SELECT generation_status
                        FROM knowledge_document_generation
                        WHERE document_id = ?
                          AND generation = ?
                        """,
                        (rs, rowNum) -> rs.getString(1),
                        row.documentId(),
                        row.generation()
                ).stream().findFirst().orElse(null);

                if ("PUBLISHED".equals(generationStatus)) {
                    Integer chunkCount = jdbcTemplate.queryForObject(
                            """
                            SELECT count(*)
                            FROM knowledge_search_projection
                            WHERE document_id = ?
                              AND generation = ?
                            """,
                            Integer.class,
                            row.documentId(),
                            row.generation()
                    );
                    KnowledgeIngestionResponse recovered =
                            new KnowledgeIngestionResponse(
                                    row.documentId(),
                                    chunkCount == null ? 0 : chunkCount
                            );
                    jdbcTemplate.update(
                            """
                            UPDATE knowledge_ingestion_request
                            SET request_status = 'SUCCEEDED',
                                response_json = ?::jsonb,
                                lease_until = NULL,
                                last_error = NULL,
                                updated_at = clock_timestamp()
                            WHERE idempotency_key = ?
                              AND request_fingerprint = ?
                            """,
                            writeResponse(recovered),
                            key,
                            fingerprint
                    );
                    return ClaimResult.replay(recovered);
                }

                jdbcTemplate.update(
                        """
                        UPDATE knowledge_document_generation
                        SET generation_status = 'FAILED',
                            failure_code = 'IDEMPOTENCY_RECLAIM',
                            last_error = 'Expired idempotency claim reclaimed',
                            cleanup_required = true,
                            failed_at = clock_timestamp()
                        WHERE document_id = ?
                          AND generation = ?
                          AND generation_kind = 'INGESTION'
                          AND generation_status = 'STAGING'
                        """,
                        row.documentId(),
                        row.generation()
                );
            }

            jdbcTemplate.update(
                    """
                    UPDATE knowledge_ingestion_request
                    SET request_status = 'IN_PROGRESS',
                        claim_id = ?,
                        generation = NULL,
                        lease_until = clock_timestamp()
                            + (? * interval '1 millisecond'),
                        response_json = NULL,
                        last_error = NULL,
                        updated_at = clock_timestamp()
                    WHERE idempotency_key = ?
                      AND request_fingerprint = ?
                    """,
                    candidateClaimId,
                    leaseDuration.toMillis(),
                    key,
                    fingerprint
            );

            return ClaimResult.claimed(new IngestionIdempotencyContext(
                    key,
                    candidateClaimId,
                    fingerprint
            ));
        });
    }

    public void renew(
            IngestionIdempotencyContext context,
            Duration leaseDuration
    ) {
        if (context == null) {
            return;
        }
        if (leaseDuration == null
                || leaseDuration.isZero()
                || leaseDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "leaseDuration must be positive"
            );
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE knowledge_ingestion_request
                SET lease_until = clock_timestamp()
                    + (? * interval '1 millisecond'),
                    updated_at = clock_timestamp()
                WHERE idempotency_key = ?
                  AND claim_id = ?
                  AND request_fingerprint = ?
                  AND request_status = 'IN_PROGRESS'
                  AND lease_until > clock_timestamp()
                """,
                leaseDuration.toMillis(),
                context.key(),
                context.claimId(),
                context.fingerprint()
        );
        if (updated != 1) {
            throw new IdempotencyConflictException(
                    "INGESTION_IDEMPOTENCY_LOST",
                    "Idempotency claim is no longer current"
            );
        }
    }

    public void attachGeneration(
            IngestionIdempotencyContext context,
            long generation
    ) {
        if (context == null) {
            return;
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE knowledge_ingestion_request
                SET generation = ?,
                    updated_at = clock_timestamp()
                WHERE idempotency_key = ?
                  AND claim_id = ?
                  AND request_fingerprint = ?
                  AND request_status = 'IN_PROGRESS'
                  AND lease_until > clock_timestamp()
                """,
                generation,
                context.key(),
                context.claimId(),
                context.fingerprint()
        );
        if (updated != 1) {
            throw new IdempotencyConflictException(
                    "INGESTION_IDEMPOTENCY_LOST",
                    "Idempotency claim expired before generation allocation"
            );
        }
    }

    public void completeInCurrentTransaction(
            IngestionIdempotencyContext context,
            KnowledgeIngestionResponse response
    ) {
        if (context == null) {
            return;
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE knowledge_ingestion_request
                SET request_status = 'SUCCEEDED',
                    response_json = ?::jsonb,
                    lease_until = NULL,
                    last_error = NULL,
                    updated_at = clock_timestamp()
                WHERE idempotency_key = ?
                  AND claim_id = ?
                  AND request_fingerprint = ?
                  AND request_status = 'IN_PROGRESS'
                  AND lease_until > clock_timestamp()
                """,
                writeResponse(response),
                context.key(),
                context.claimId(),
                context.fingerprint()
        );
        if (updated != 1) {
            throw new IdempotencyConflictException(
                    "INGESTION_IDEMPOTENCY_LOST",
                    "Idempotency claim is no longer current"
            );
        }
    }

    public void fail(
            IngestionIdempotencyContext context,
            String error
    ) {
        if (context == null) {
            return;
        }
        jdbcTemplate.update(
                """
                UPDATE knowledge_ingestion_request
                SET request_status = 'FAILED',
                    lease_until = NULL,
                    last_error = ?,
                    updated_at = clock_timestamp()
                WHERE idempotency_key = ?
                  AND claim_id = ?
                  AND request_fingerprint = ?
                  AND request_status = 'IN_PROGRESS'
                """,
                sanitize(error),
                context.key(),
                context.claimId(),
                context.fingerprint()
        );
    }

    private RequestRow selectForUpdate(String key) {
        return jdbcTemplate.query(
                """
                SELECT idempotency_key, document_id, request_fingerprint,
                       request_status, generation, claim_id, lease_until,
                       response_json::text AS response_json
                FROM knowledge_ingestion_request
                WHERE idempotency_key = ?
                FOR UPDATE
                """,
                this::map,
                key
        ).stream().findFirst().orElseThrow(() ->
                new IllegalStateException("Idempotency row disappeared"));
    }

    private RequestRow map(ResultSet rs, int rowNum) throws SQLException {
        var lease = rs.getTimestamp("lease_until");
        long generation = rs.getLong("generation");
        return new RequestRow(
                rs.getString("document_id"),
                rs.getString("request_fingerprint"),
                rs.getString("request_status"),
                rs.wasNull() ? null : generation,
                rs.getObject("claim_id", UUID.class),
                lease == null ? null : lease.toInstant(),
                rs.getString("response_json")
        );
    }

    private String writeResponse(KnowledgeIngestionResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Cannot serialize idempotent response",
                    exception
            );
        }
    }

    private KnowledgeIngestionResponse readResponse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException(
                    "Succeeded idempotency row has no response"
            );
        }
        try {
            return objectMapper.readValue(
                    json,
                    KnowledgeIngestionResponse.class
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Cannot deserialize idempotent response",
                    exception
            );
        }
    }

    private String sanitize(String error) {
        if (error == null || error.isBlank()) {
            return "ingestion failed";
        }
        String value = error.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private record RequestRow(
            String documentId,
            String fingerprint,
            String status,
            Long generation,
            UUID claimId,
            Instant leaseUntil,
            String responseJson
    ) {
    }

    public record ClaimResult(
            Status status,
            IngestionIdempotencyContext context,
            KnowledgeIngestionResponse response,
            Long retryAfterSeconds
    ) {
        public static ClaimResult claimed(
                IngestionIdempotencyContext context
        ) {
            return new ClaimResult(Status.CLAIMED, context, null, null);
        }

        public static ClaimResult replay(
                KnowledgeIngestionResponse response
        ) {
            return new ClaimResult(Status.REPLAY, null, response, null);
        }

        public static ClaimResult inProgress(long retryAfterSeconds) {
            return new ClaimResult(
                    Status.IN_PROGRESS,
                    null,
                    null,
                    Math.max(1L, retryAfterSeconds)
            );
        }

        public enum Status {
            CLAIMED,
            REPLAY,
            IN_PROGRESS
        }
    }
}
