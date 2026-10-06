package kz.alimbetov.akmai.rag.learning;

import java.util.List;
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
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (requestId == null || reason == null) {
            throw new IllegalArgumentException("requestId and reason are required");
        }
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO rag_feedback (
                    feedback_id,
                    idempotency_key,
                    request_id,
                    reason,
                    details
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """,
                UUID.randomUUID(),
                idempotencyKey,
                requestId,
                reason.name(),
                details
        );
        if (inserted == 1) {
            return Result.CREATED;
        }

        List<ExistingFeedback> existing = jdbcTemplate.query(
                """
                SELECT request_id, reason, details
                FROM rag_feedback
                WHERE idempotency_key = ?
                """,
                (rs, rowNum) -> new ExistingFeedback(
                        rs.getObject("request_id", UUID.class),
                        RagFeedbackReason.valueOf(rs.getString("reason")),
                        rs.getString("details")
                ),
                idempotencyKey
        );
        if (existing.isEmpty()) {
            throw new IllegalStateException(
                    "Feedback idempotency conflict could not be resolved"
            );
        }
        ExistingFeedback value = existing.getFirst();
        if (!requestId.equals(value.requestId())
                || reason != value.reason()
                || !java.util.Objects.equals(details, value.details())) {
            throw new IllegalArgumentException(
                    "Idempotency-Key is already used for another feedback payload"
            );
        }
        return Result.REPLAY;
    }

    public enum Result {
        CREATED,
        REPLAY
    }

    private record ExistingFeedback(
            UUID requestId,
            RagFeedbackReason reason,
            String details
    ) {
    }
}
