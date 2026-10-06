package kz.alimbetov.akmai.rag.learning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RagLearningEventRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RagLearningEventRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(RagLearningEvent event) {
        if (event == null) {
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO rag_learning_event (
                    event_id,
                    request_id,
                    query_fingerprint,
                    access_levels,
                    language,
                    query_class,
                    corpus_version,
                    embedding_profile_id,
                    retrieval_policy_version,
                    learning_policy_version,
                    grounding_policy_version,
                    answer_status,
                    grounding_status,
                    retrieved_count,
                    selected_count,
                    cited_count,
                    total_latency_ms,
                    trace_json,
                    created_at
                ) VALUES (
                    ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?
                )
                ON CONFLICT (request_id) DO NOTHING
                """,
                event.eventId(),
                event.requestId(),
                event.queryFingerprint(),
                writeJson(event.accessLevels().stream().sorted().toList()),
                event.language(),
                event.queryClass(),
                event.corpusVersion(),
                event.embeddingProfileId(),
                event.retrievalPolicyVersion(),
                event.learningPolicyVersion(),
                event.groundingPolicyVersion(),
                event.answerStatus().name(),
                event.groundingStatus().name(),
                event.retrievedCount(),
                event.selectedCount(),
                event.citedCount(),
                event.totalLatencyMs(),
                writeJson(event.trace()),
                java.sql.Timestamp.from(event.createdAt())
        );
    }

    public Optional<Set<Long>> findAccessLevels(UUID requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        var values = jdbcTemplate.query(
                """
                SELECT access_levels::text
                FROM rag_learning_event
                WHERE request_id = ?
                """,
                (rs, rowNum) -> readScope(rs.getString(1)),
                requestId
        );
        return values.stream().findFirst();
    }

    public boolean exists(UUID requestId) {
        if (requestId == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_learning_event WHERE request_id = ?",
                Integer.class,
                requestId
        );
        return count != null && count > 0;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize RAG learning event", exception);
        }
    }

    private Set<Long> readScope(String json) {
        try {
            Long[] values = objectMapper.readValue(json, Long[].class);
            LinkedHashSet<Long> result = new LinkedHashSet<>();
            Arrays.stream(values)
                    .filter(java.util.Objects::nonNull)
                    .filter(value -> value > 0)
                    .forEach(result::add);
            return Set.copyOf(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read RAG learning access scope", exception);
        }
    }
}
