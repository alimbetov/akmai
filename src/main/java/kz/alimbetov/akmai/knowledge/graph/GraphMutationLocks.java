package kz.alimbetov.akmai.knowledge.graph;

import java.util.Collection;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Shared transaction-scoped lock primitive for graph mutations.
 *
 * <p>Callers must already be inside a database transaction. Nodes are sorted
 * canonically before any lock is acquired. Normal graph mutation locks eligible
 * lifecycle rows first, then the exact published generation rows, and finally
 * node advisory locks. Semantic retirement uses the same canonical row/advisory
 * order but permits lifecycle/generation rows that are no longer eligible so an
 * obsolete prior can still be removed safely.</p>
 */
@Component
public class GraphMutationLocks {

    private static final String NODE_LOCK_PREFIX = "akmai:adaptive-graph:node:";

    private final JdbcTemplate jdbcTemplate;

    public GraphMutationLocks(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockEligiblePublishedNodes(Collection<ChunkGraphNode> nodes) {
        requireActiveTransaction();
        TreeSet<ChunkGraphNode> lockOrder = canonicalOrder(nodes);

        lockOrder.forEach(this::lockEligibleLifecycle);
        lockOrder.forEach(this::lockPublishedGeneration);
        lockOrder.forEach(this::lockNode);
    }

    /**
     * Non-blocking variant used by horizontally replicated maintenance workers.
     * Lifecycle/generation rows are still locked in canonical order before node
     * advisory locks. If another graph mutation owns any node advisory lock, the
     * current transaction reports false and should be rolled back/ended without
     * touching graph rows; any advisory locks acquired earlier in this attempt
     * are transaction-scoped and are released with that transaction.
     */
    public boolean tryLockEligiblePublishedNodes(
            Collection<ChunkGraphNode> nodes
    ) {
        requireActiveTransaction();
        TreeSet<ChunkGraphNode> lockOrder = canonicalOrder(nodes);

        lockOrder.forEach(this::lockEligibleLifecycle);
        lockOrder.forEach(this::lockPublishedGeneration);
        for (ChunkGraphNode node : lockOrder) {
            if (!tryLockNode(node)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Locks a pair for semantic-prior retirement. Retirement is allowed after
     * TTL expiry, publication replacement, or another authoritative lifecycle
     * invalidation, so row presence/status is not itself an eligibility gate.
     * Existing lifecycle and generation rows are still locked before the same
     * advisory node locks used by normal graph mutations.
     */
    public void lockRetirementNodes(Collection<ChunkGraphNode> nodes) {
        requireActiveTransaction();
        TreeSet<ChunkGraphNode> lockOrder = canonicalOrder(nodes);

        lockOrder.forEach(this::lockLifecycleIfPresent);
        lockOrder.forEach(this::lockGenerationIfPresent);
        lockOrder.forEach(this::lockNode);
    }

    /** Non-blocking counterpart of {@link #lockRetirementNodes(Collection)}. */
    public boolean tryLockRetirementNodes(Collection<ChunkGraphNode> nodes) {
        requireActiveTransaction();
        TreeSet<ChunkGraphNode> lockOrder = canonicalOrder(nodes);

        lockOrder.forEach(this::lockLifecycleIfPresent);
        lockOrder.forEach(this::lockGenerationIfPresent);
        for (ChunkGraphNode node : lockOrder) {
            if (!tryLockNode(node)) {
                return false;
            }
        }
        return true;
    }

    private void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "graph mutation locks require an active database transaction"
            );
        }
    }

    private TreeSet<ChunkGraphNode> canonicalOrder(
            Collection<ChunkGraphNode> nodes
    ) {
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
        return lockOrder;
    }

    private void lockEligibleLifecycle(ChunkGraphNode node) {
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
            throw ineligibleNode();
        }
    }

    private void lockPublishedGeneration(ChunkGraphNode node) {
        Integer published = jdbcTemplate.query(
                """
                SELECT 1
                FROM knowledge_document_generation
                WHERE document_id = ?
                  AND generation = ?
                  AND access_level = ?
                  AND generation_status = 'PUBLISHED'
                FOR SHARE
                """,
                (rs, rowNum) -> rs.getInt(1),
                node.documentId(),
                node.generation(),
                node.accessLevel()
        ).stream().findFirst().orElse(null);

        if (published == null) {
            throw ineligibleNode();
        }
    }

    private void lockLifecycleIfPresent(ChunkGraphNode node) {
        jdbcTemplate.query(
                """
                SELECT 1
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                  AND access_level = ?
                FOR SHARE
                """,
                rs -> {
                },
                node.documentId(),
                node.accessLevel()
        );
    }

    private void lockGenerationIfPresent(ChunkGraphNode node) {
        jdbcTemplate.query(
                """
                SELECT 1
                FROM knowledge_document_generation
                WHERE document_id = ?
                  AND generation = ?
                  AND access_level = ?
                FOR SHARE
                """,
                rs -> {
                },
                node.documentId(),
                node.generation(),
                node.accessLevel()
        );
    }

    private IllegalStateException ineligibleNode() {
        return new IllegalStateException(
                "graph mutation requires READY/ACTIVE/PUBLISHED/non-expired generation"
        );
    }

    private void lockNode(ChunkGraphNode node) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> {
                },
                NODE_LOCK_PREFIX + node.lockKey()
        );
    }

    private boolean tryLockNode(ChunkGraphNode node) {
        Boolean locked = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                Boolean.class,
                NODE_LOCK_PREFIX + node.lockKey()
        );
        return Boolean.TRUE.equals(locked);
    }
}
