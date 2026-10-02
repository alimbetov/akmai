package kz.alimbetov.akmai.knowledge.ingestion;

import java.sql.Timestamp;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PublicationOutcomeResolver {

    private final JdbcTemplate jdbcTemplate;

    public PublicationOutcomeResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Outcome resolve(
            String documentId,
            long generation,
            IngestionIdempotencyContext idempotency
    ) {
        if (idempotency != null) {
            String status = jdbcTemplate.query(
                    """
                    SELECT request_status
                    FROM knowledge_ingestion_request
                    WHERE idempotency_key = ?
                      AND claim_id = ?
                      AND request_fingerprint = ?
                    """,
                    (rs, rowNum) -> rs.getString(1),
                    idempotency.key(),
                    idempotency.claimId(),
                    idempotency.fingerprint()
            ).stream().findFirst().orElse(null);
            if ("SUCCEEDED".equals(status)) {
                return Outcome.COMMITTED;
            }
        }

        return jdbcTemplate.query(
                """
                SELECT generation_status, published_at, failure_code
                FROM knowledge_document_generation
                WHERE document_id = ?
                  AND generation = ?
                """,
                (rs, rowNum) -> {
                    String status = rs.getString("generation_status");
                    Timestamp publishedAt = rs.getTimestamp("published_at");
                    String failureCode = rs.getString("failure_code");
                    if (publishedAt != null
                            && ("PUBLISHED".equals(status)
                            || "RETIRED".equals(status)
                            || "CLEANED".equals(status))) {
                        return Outcome.COMMITTED;
                    }
                    if ("FAILED".equals(status)
                            && "SUPERSEDED".equals(failureCode)) {
                        return Outcome.SUPERSEDED;
                    }
                    return Outcome.NOT_COMMITTED;
                },
                documentId,
                generation
        ).stream().findFirst().orElse(Outcome.NOT_COMMITTED);
    }

    public enum Outcome {
        COMMITTED,
        SUPERSEDED,
        NOT_COMMITTED
    }
}
