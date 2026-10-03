package kz.alimbetov.akmai.knowledge.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.lifecycle.GenerationIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditEventRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditEventRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void append(
            String eventType,
            GenerationIdentity identity,
            UUID correlationId,
            String source,
            Map<String, Object> details
    ) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }

        jdbcTemplate.update(
                """
                INSERT INTO knowledge_audit_event (
                    event_at,
                    event_id,
                    event_type,
                    document_id,
                    generation,
                    access_level,
                    correlation_id,
                    source,
                    details_json
                ) VALUES (
                    clock_timestamp(),
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?::jsonb
                )
                """,
                UUID.randomUUID(),
                eventType,
                identity.documentId(),
                identity.generation(),
                identity.accessLevel(),
                correlationId,
                source,
                json(details)
        );
    }

    private String json(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(
                    details == null ? Map.of() : details
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Cannot serialize audit details",
                    exception
            );
        }
    }
}
