package kz.alimbetov.akmai.knowledge.graph;

import java.util.Collection;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Shared transaction-scoped lock primitive for graph mutations.
 *
 * <p>Callers must already be inside a database transaction. Nodes are sorted
 * canonically before any lock is acquired, lifecycle rows are locked first,
 * then node advisory locks are acquired in the same deterministic order.</p>
 */
@Component
public class GraphMutationLocks {

    private static final String NODE_LOCK_PREFIX = "akmai:adaptive-graph:node:";

    private final JdbcTemplate jdbcTemplate;

    public GraphMutationLocks(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockEligiblePublishedNodes(Collection<ChunkGraphNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("graph mutation nodes must not be empty");
        }

        TreeSet<ChunkGraphNode> lockOrder = new TreeSet<>();
        for (ChunkGraphNode node : nodes) {
            if (node == null) {
                throw new IllegalArgumentException("graph mutation node must not be null");
            }
            lockOrder.add(node);
        }

        lockOrder.forEach(this::lockEligiblePublishedGeneration);
        lockOrder.forEach(this::lockNode);
    }

    private void lockEligiblePublishedGeneration(ChunkGraphNode node) {
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
                    "graph mutation requires READY/ACTIVE/PUBLISHED/non-expired generation"
            );
        }
    }

    private void lockNode(ChunkGraphNode node) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> {
                },
                NODE_LOCK_PREFIX + node.lockKey()
        );
    }
}
