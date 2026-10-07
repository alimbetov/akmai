package kz.alimbetov.akmai.rag.learning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RagLearningEventRepository {

    private static final String LEGACY_SOURCE = "legacy-or-unknown";

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

    public List<RouterTrainingSample> findRouterTrainingSamples(
            Instant since,
            int limit
    ) {
        if (since == null || limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT query_fingerprint,
                       query_class,
                       source_fingerprint,
                       trace_json::text,
                       total_latency_ms,
                       created_at
                FROM (
                    SELECT DISTINCT ON (query_fingerprint, query_class)
                           query_fingerprint,
                           query_class,
                           COALESCE(
                               NULLIF(trace_json ->> 'sourceFingerprint', ''),
                               'legacy-or-unknown'
                           ) AS source_fingerprint,
                           trace_json,
                           total_latency_ms,
                           created_at
                    FROM rag_learning_event
                    WHERE created_at >= ?
                      AND answer_status = 'GROUNDED'
                      AND grounding_status = 'SUPPORTED'
                      AND cited_count > 0
                      AND query_class IS NOT NULL
                      AND query_class <> 'ANALYSIS_UNAVAILABLE'
                      AND COALESCE(
                            (trace_json ->> 'retrievalDegraded')::boolean,
                            false
                          ) = false
                      AND COALESCE(
                            (trace_json ->> 'retrievalCriticalFailure')::boolean,
                            false
                          ) = false
                    ORDER BY query_fingerprint,
                             query_class,
                             CASE
                                 WHEN NULLIF(trace_json ->> 'sourceFingerprint', '')
                                      IS NULL THEN 1
                                 ELSE 0
                             END,
                             created_at ASC
                ) deduplicated
                ORDER BY created_at DESC, query_fingerprint
                LIMIT ?
                """,
                (rs, rowNum) -> {
                    Map<String, Object> trace = readMap(rs.getString("trace_json"));
                    return new RouterTrainingSample(
                            rs.getString("query_fingerprint"),
                            rs.getString("query_class"),
                            rs.getString("source_fingerprint"),
                            intMap(trace.get("selectedLaneContributions")),
                            intMap(trace.get("citedLaneContributions")),
                            rs.getLong("total_latency_ms"),
                            rs.getTimestamp("created_at").toInstant()
                    );
                },
                java.sql.Timestamp.from(since),
                limit
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize RAG learning event", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return Map.copyOf(objectMapper.readValue(json, Map.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read RAG learning trace", exception);
        }
    }

    private Map<String, Integer> intMap(Object value) {
        if (!(value instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key instanceof String text && item instanceof Number number) {
                int count = Math.max(0, number.intValue());
                if (count > 0) {
                    result.put(text, count);
                }
            }
        });
        return Map.copyOf(result);
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

    public record RouterTrainingSample(
            String queryFingerprint,
            String queryClass,
            String sourceFingerprint,
            Map<String, Integer> selectedLaneContributions,
            Map<String, Integer> citedLaneContributions,
            long totalLatencyMs,
            Instant createdAt
    ) {
        public RouterTrainingSample(
                String queryFingerprint,
                String queryClass,
                Map<String, Integer> selectedLaneContributions,
                Map<String, Integer> citedLaneContributions,
                long totalLatencyMs,
                Instant createdAt
        ) {
            this(
                    queryFingerprint,
                    queryClass,
                    LEGACY_SOURCE,
                    selectedLaneContributions,
                    citedLaneContributions,
                    totalLatencyMs,
                    createdAt
            );
        }

        public RouterTrainingSample {
            sourceFingerprint = sourceFingerprint == null
                    || sourceFingerprint.isBlank()
                    ? LEGACY_SOURCE
                    : sourceFingerprint.trim();
            selectedLaneContributions = selectedLaneContributions == null
                    ? Map.of()
                    : Map.copyOf(selectedLaneContributions);
            citedLaneContributions = citedLaneContributions == null
                    ? Map.of()
                    : Map.copyOf(citedLaneContributions);
            totalLatencyMs = Math.max(0, totalLatencyMs);
        }

        public boolean attributedSource() {
            return !LEGACY_SOURCE.equals(sourceFingerprint);
        }
    }
}
