package kz.alimbetov.akmai.knowledge.graph;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Transaction-scoped lifecycle validation for graph mutations.
 *
 * <p>The two methods intentionally preserve the repository's existing policy
 * split: online reinforcement requires the published ACTIVE generation, while
 * semantic mutation additionally requires READY and non-expired lifecycle
 * state.</p>
 */
@Component
public class GraphLifecycleGuard {

    private final JdbcTemplate jdbcTemplate;

    public GraphLifecycleGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockPublishedActive(ChunkGraphNode node) {
        requireNode(node);
        Integer published = jdbcTemplate.query(
                """
                SELECT 1
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                  AND access_level = ?
                  AND published_generation = ?
                  AND retention_status = 'ACTIVE'
                FOR SHARE
                """,
                (rs, rowNum) -> rs.getInt(1),
                node.documentId(),
                node.accessLevel(),
                node.generation()
        ).stream().findFirst().orElse(null);
        if (published == null) {
            throw new IllegalStateException(
                    "graph mutation requires ACTIVE/PUBLISHED generation"
            );
        }
    }

    public void lockPublishedReadyActive(ChunkGraphNode node) {
        requireNode(node);
        Integer published = jdbcTemplate.query(
                """
                SELECT 1
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                  AND access_level = ?
                  AND published_generation = ?
                  AND lifecycle_status = 'READY'
                  AND retention_status = 'ACTIVE'
                  AND (
                      expires_at IS NULL
                      OR expires_at > clock_timestamp()
                  )
                FOR SHARE
                """,
                (rs, rowNum) -> rs.getInt(1),
                node.documentId(),
                node.accessLevel(),
                node.generation()
        ).stream().findFirst().orElse(null);
        if (published == null) {
            throw new IllegalStateException(
                    "semantic graph mutation requires eligible READY/ACTIVE/PUBLISHED generation"
            );
        }
    }

    private static void requireNode(ChunkGraphNode node) {
        if (node == null) {
            throw new IllegalArgumentException("graph lifecycle node must not be null");
        }
    }
}
