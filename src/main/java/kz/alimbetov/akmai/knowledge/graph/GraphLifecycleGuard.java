package kz.alimbetov.akmai.knowledge.graph;

import java.util.Collection;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Centralized lifecycle locking for adaptive-graph mutation paths.
 *
 * <p>Semantic writers require READY + ACTIVE + published generation + TTL.
 * Learned reinforcement preserves its historical contract and requires ACTIVE
 * + published generation. All checks acquire a shared row lock.</p>
 */
@Component
public class GraphLifecycleGuard {

    private final JdbcTemplate jdbcTemplate;

    public GraphLifecycleGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockSemanticEligibleCanonical(Collection<ChunkGraphNode> nodes) {
        ordered(nodes).forEach(this::lockSemanticEligible);
    }

    public void lockLearnedEligibleCanonical(Collection<ChunkGraphNode> nodes) {
        ordered(nodes).forEach(this::lockLearnedEligible);
    }

    public void lockSemanticEligible(ChunkGraphNode node) {
        requireNode(node);
        Integer eligible = jdbcTemplate.query(
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
        if (eligible == null) {
            throw new IllegalStateException(
                    "semantic graph mutation requires eligible ACTIVE/PUBLISHED generation"
            );
        }
    }

    public void lockLearnedEligible(ChunkGraphNode node) {
        requireNode(node);
        Integer eligible = jdbcTemplate.query(
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
        if (eligible == null) {
            throw new IllegalStateException(
                    "learned graph mutation requires ACTIVE/PUBLISHED generation"
            );
        }
    }

    private TreeSet<ChunkGraphNode> ordered(Collection<ChunkGraphNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("graph lifecycle nodes must not be empty");
        }
        TreeSet<ChunkGraphNode> ordered = new TreeSet<>();
        for (ChunkGraphNode node : nodes) {
            requireNode(node);
            ordered.add(node);
        }
        return ordered;
    }

    private void requireNode(ChunkGraphNode node) {
        if (node == null) {
            throw new IllegalArgumentException("graph lifecycle node must not be null");
        }
    }
}
