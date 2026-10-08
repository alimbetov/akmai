package kz.alimbetov.akmai.knowledge.graph;

import java.util.Collection;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Transaction-scoped advisory locking for adaptive-graph nodes.
 *
 * <p>Callers must execute this primitive inside an active database transaction.
 * Multiple nodes are always acquired in canonical {@link ChunkGraphNode} order
 * to keep lock ordering deterministic across writers.</p>
 */
@Component
public class GraphNodeLockManager {

    private static final String LOCK_PREFIX = "akmai:adaptive-graph:node:";

    private final JdbcTemplate jdbcTemplate;

    public GraphNodeLockManager(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockCanonical(Collection<ChunkGraphNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("graph lock nodes must not be empty");
        }
        TreeSet<ChunkGraphNode> ordered = new TreeSet<>();
        for (ChunkGraphNode node : nodes) {
            if (node == null) {
                throw new IllegalArgumentException("graph lock node must not be null");
            }
            ordered.add(node);
        }
        ordered.forEach(this::lock);
    }

    public void lock(ChunkGraphNode node) {
        if (node == null) {
            throw new IllegalArgumentException("graph lock node must not be null");
        }
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> {
                },
                LOCK_PREFIX + node.lockKey()
        );
    }
}
