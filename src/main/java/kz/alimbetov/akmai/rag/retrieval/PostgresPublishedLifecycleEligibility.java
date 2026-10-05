package kz.alimbetov.akmai.rag.retrieval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PostgresPublishedLifecycleEligibility
        implements PublishedLifecycleEligibility {

    private static final int MAX_IDENTITIES_PER_QUERY = 256;

    private final JdbcTemplate jdbcTemplate;

    public PostgresPublishedLifecycleEligibility(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<RetrievalHit> filter(
            List<RetrievalHit> hits,
            Set<Long> accessLevels
    ) {
        requireAccessLevels(accessLevels);
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        List<GenerationKey> requested = hits.stream()
                .filter(RetrievalHit::hasRoutingIdentity)
                .filter(hit -> accessLevels.contains(hit.accessLevel()))
                .map(GenerationKey::of)
                .distinct()
                .toList();
        if (requested.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<GenerationKey> eligible = new LinkedHashSet<>();
        for (int from = 0; from < requested.size(); from += MAX_IDENTITIES_PER_QUERY) {
            int to = Math.min(requested.size(), from + MAX_IDENTITIES_PER_QUERY);
            eligible.addAll(findEligible(requested.subList(from, to)));
        }

        return hits.stream()
                .filter(RetrievalHit::hasRoutingIdentity)
                .filter(hit -> accessLevels.contains(hit.accessLevel()))
                .filter(hit -> eligible.contains(GenerationKey.of(hit)))
                .toList();
    }

    private List<GenerationKey> findEligible(List<GenerationKey> requested) {
        String tuple = "(?, ?, ?)";
        String values = String.join(
                ", ",
                java.util.Collections.nCopies(requested.size(), tuple)
        );
        String sql = """
                SELECT l.access_level,
                       l.document_id,
                       l.published_generation
                FROM knowledge_document_lifecycle l
                WHERE l.retention_status = 'ACTIVE'
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

    private void requireAccessLevels(Set<Long> accessLevels) {
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    "accessLevels must not be empty"
            );
        }
        if (accessLevels.stream()
                .anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalArgumentException(
                    "accessLevels must contain positive values"
            );
        }
    }

    private record GenerationKey(
            long accessLevel,
            String documentId,
            long generation
    ) {
        static GenerationKey of(RetrievalHit hit) {
            return new GenerationKey(
                    hit.accessLevel(),
                    hit.documentId(),
                    hit.generation()
            );
        }
    }
}
