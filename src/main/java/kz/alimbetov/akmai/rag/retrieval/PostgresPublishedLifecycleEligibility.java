package kz.alimbetov.akmai.rag.retrieval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PostgresPublishedLifecycleEligibility
        implements PublishedLifecycleEligibility {

    private static final int MAX_IDENTITIES_PER_QUERY = 256;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    PostgresPublishedLifecycleEligibility(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null);
    }

    @Autowired
    public PostgresPublishedLifecycleEligibility(
            JdbcTemplate jdbcTemplate,
            @Qualifier("retrievalTransactionTemplate")
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
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

        LinkedHashSet<GenerationKey> eligible = read(() -> {
            LinkedHashSet<GenerationKey> values = new LinkedHashSet<>();
            for (int from = 0; from < requested.size(); from += MAX_IDENTITIES_PER_QUERY) {
                int to = Math.min(requested.size(), from + MAX_IDENTITIES_PER_QUERY);
                values.addAll(findEligible(requested.subList(from, to)));
            }
            return values;
        });

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

    private <T> T read(Supplier<T> action) {
        if (transactionTemplate == null) {
            return action.get();
        }
        T value = transactionTemplate.execute(status -> action.get());
        if (value == null) {
            throw new IllegalStateException(
                    "retrieval lifecycle transaction returned no result"
            );
        }
        return value;
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
