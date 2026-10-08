package kz.alimbetov.akmai.knowledge.projection;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Synchronous lifecycle fence for materialized published projections.
 * Expired-but-still-ACTIVE rows must not participate in expansion, ranking
 * or context budgeting while asynchronous retention cleanup is pending.
 */
@Component
public class PublishedProjectionLifecycleEligibility {

    private static final int MAX_IDENTITIES_PER_QUERY = 256;

    private final JdbcTemplate jdbcTemplate;

    public PublishedProjectionLifecycleEligibility(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SearchProjection> filter(List<SearchProjection> projections) {
        if (projections == null || projections.isEmpty()) {
            return List.of();
        }

        List<GenerationKey> requested = projections.stream()
                .filter(java.util.Objects::nonNull)
                .map(GenerationKey::of)
                .distinct()
                .toList();
        if (requested.isEmpty()) {
            return List.of();
        }

        Set<GenerationKey> eligible = new LinkedHashSet<>();
        for (int from = 0; from < requested.size(); from += MAX_IDENTITIES_PER_QUERY) {
            int to = Math.min(requested.size(), from + MAX_IDENTITIES_PER_QUERY);
            eligible.addAll(findEligible(requested.subList(from, to)));
        }

        return projections.stream()
                .filter(java.util.Objects::nonNull)
                .filter(value -> eligible.contains(GenerationKey.of(value)))
                .toList();
    }

    private List<GenerationKey> findEligible(List<GenerationKey> requested) {
        String values = String.join(
                ", ",
                java.util.Collections.nCopies(requested.size(), "(?, ?, ?)")
        );
        String sql = """
                SELECT l.access_level,
                       l.document_id,
                       l.published_generation
                FROM knowledge_document_lifecycle l
                WHERE l.lifecycle_status = 'READY'
                  AND l.retention_status = 'ACTIVE'
                  AND (l.expires_at IS NULL
                       OR l.expires_at > clock_timestamp())
                  AND (l.access_level,
                       l.document_id,
                       l.published_generation) IN (
                """ + values + ")";

        return jdbcTemplate.query(
                sql,
                ps -> {
                    int index = 1;
                    for (GenerationKey key : requested) {
                        ps.setLong(index++, key.accessLevel());
                        ps.setString(index++, key.documentId());
                        ps.setLong(index++, key.generation());
                    }
                },
                (rs, rowNum) -> new GenerationKey(
                        rs.getLong("access_level"),
                        rs.getString("document_id"),
                        rs.getLong("published_generation")
                )
        );
    }

    private record GenerationKey(
            long accessLevel,
            String documentId,
            long generation
    ) {
        private static GenerationKey of(SearchProjection projection) {
            return new GenerationKey(
                    projection.accessLevel(),
                    projection.documentId(),
                    projection.generation()
            );
        }
    }
}
