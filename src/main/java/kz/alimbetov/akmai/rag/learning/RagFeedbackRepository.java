package kz.alimbetov.akmai.rag.learning;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RagFeedbackRepository {

    private final JdbcTemplate jdbcTemplate;

    public RagFeedbackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Result record(
            String idempotencyKey,
            UUID requestId,
            RagFeedbackReason reason,
            String details
    ) {
        return record(
                idempotencyKey,
                requestId,
                reason,
                details,
                RagFeedbackTrustClass.USER_UNVERIFIED,
                null
        );
    }

    public Result record(
            String idempotencyKey,
            UUID requestId,
            RagFeedbackReason reason,
            String details,
            RagFeedbackTrustClass trustClass,
            String sourceFingerprint
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (requestId == null || reason == null || trustClass == null) {
            throw new IllegalArgumentException(
                    "requestId, reason and trustClass are required"
            );
        }
        String normalizedSource = normalizeFingerprint(sourceFingerprint);
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO rag_feedback (
                    feedback_id,
                    idempotency_key,
                    request_id,
                    reason,
                    details,
                    trust_class,
                    source_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(),
                idempotencyKey,
                requestId,
                reason.name(),
                details,
                trustClass.name(),
                normalizedSource
        );
        if (inserted == 1) {
            return Result.CREATED;
        }

        ExistingFeedback existing = findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> findByRequestId(requestId).orElse(null));
        if (existing == null) {
            throw new IllegalStateException(
                    "Feedback conflict could not be resolved"
            );
        }
        if (!requestId.equals(existing.requestId())
                || reason != existing.reason()
                || !Objects.equals(details, existing.details())
                || trustClass != existing.trustClass()
                || !Objects.equals(normalizedSource, existing.sourceFingerprint())) {
            if (requestId.equals(existing.requestId())) {
                throw new IllegalArgumentException(
                        "RAG requestId already has a different feedback signal"
                );
            }
            throw new IllegalArgumentException(
                    "Idempotency-Key is already used for another feedback payload"
            );
        }
        return Result.REPLAY;
    }

    private java.util.Optional<ExistingFeedback> findByIdempotencyKey(String key) {
        return query(
                """
                SELECT request_id, reason, details, trust_class, source_fingerprint
                FROM rag_feedback
                WHERE idempotency_key = ?
                """,
                key
        );
    }

    private java.util.Optional<ExistingFeedback> findByRequestId(UUID requestId) {
        return query(
                """
                SELECT request_id, reason, details, trust_class, source_fingerprint
                FROM rag_feedback
                WHERE request_id = ?
                """,
                requestId
        );
    }

    private java.util.Optional<ExistingFeedback> query(String sql, Object argument) {
        List<ExistingFeedback> existing = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ExistingFeedback(
                        rs.getObject("request_id", UUID.class),
                        RagFeedbackReason.valueOf(rs.getString("reason")),
                        rs.getString("details"),
                        RagFeedbackTrustClass.valueOf(rs.getString("trust_class")),
                        rs.getString("source_fingerprint")
                ),
                argument
        );
        return existing.stream().findFirst();
    }

    private String normalizeFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() != 64) {
            throw new IllegalArgumentException(
                    "sourceFingerprint must be a SHA-256/HMAC hex value"
            );
        }
        return normalized;
    }

    public enum Result {
        CREATED,
        REPLAY
    }

    private record ExistingFeedback(
            UUID requestId,
            RagFeedbackReason reason,
            String details,
            RagFeedbackTrustClass trustClass,
            String sourceFingerprint
    ) {
    }
}
