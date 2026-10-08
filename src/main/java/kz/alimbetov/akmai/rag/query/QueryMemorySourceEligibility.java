package kz.alimbetov.akmai.rag.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Prevents derived query memory from outliving the currently published source
 * material that justified it. Historical notes are optimization hints only;
 * they are dropped when their cited chunks are no longer ACTIVE, unexpired and
 * published for the caller's ACL scope.
 */
@Component
public class QueryMemorySourceEligibility {

    private final JdbcTemplate jdbcTemplate;

    public QueryMemorySourceEligibility(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SemanticQueryMemory.MemoryMatch> filter(
            List<SemanticQueryMemory.MemoryMatch> matches,
            Set<Long> accessLevels
    ) {
        if (matches == null || matches.isEmpty()
                || accessLevels == null || accessLevels.isEmpty()) {
            return List.of();
        }

        List<SemanticQueryMemory.MemoryMatch> result = new ArrayList<>();
        for (SemanticQueryMemory.MemoryMatch match : matches) {
            List<SemanticQueryMemory.MemoryObservation> observations =
                    match.observations().stream()
                            .filter(observation -> eligible(
                                    observation.sourceRefs(),
                                    accessLevels
                            ))
                            .toList();
            if (!observations.isEmpty()) {
                result.add(new SemanticQueryMemory.MemoryMatch(
                        match.clusterId(),
                        match.similarity(),
                        match.observationCount(),
                        observations
                ));
            }
        }
        return List.copyOf(result);
    }

    private boolean eligible(List<String> sourceRefs, Set<Long> accessLevels) {
        if (sourceRefs == null || sourceRefs.isEmpty()) {
            return false;
        }
        for (String sourceRef : sourceRefs) {
            SourceRef parsed = parse(sourceRef);
            if (parsed == null || !eligible(parsed, accessLevels)) {
                return false;
            }
        }
        return true;
    }

    private boolean eligible(SourceRef source, Set<Long> accessLevels) {
        Integer count = jdbcTemplate.query(
                """
                SELECT count(*)
                FROM knowledge_search_projection p
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = p.document_id
                 AND l.access_level = p.access_level
                 AND l.published_generation = p.generation
                WHERE p.document_id = ?
                  AND p.chunk_id = ?
                  AND p.access_level = ANY (?::bigint[])
                  AND l.lifecycle_status = 'READY'
                  AND l.retention_status = 'ACTIVE'
                  AND (l.expires_at IS NULL
                       OR l.expires_at > clock_timestamp())
                """,
                ps -> {
                    ps.setString(1, source.documentId());
                    ps.setString(2, source.chunkId());
                    ps.setArray(
                            3,
                            ps.getConnection().createArrayOf(
                                    "bigint",
                                    accessLevels.stream()
                                            .sorted()
                                            .toArray(Long[]::new)
                            )
                    );
                },
                rs -> rs.next() ? rs.getInt(1) : 0
        );
        return count != null && count > 0;
    }

    private SourceRef parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator >= value.length() - 1) {
            return null;
        }
        return new SourceRef(
                value.substring(0, separator),
                value.substring(separator + 1)
        );
    }

    private record SourceRef(String documentId, String chunkId) {
    }
}
