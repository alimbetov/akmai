package kz.alimbetov.akmai.knowledge.graph.dream;

import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;

/** Canonical undirected pair used by Dream persistence and deduplication. */
public record DreamPair(ChunkGraphNode first, ChunkGraphNode second) {

    public DreamPair {
        if (first == null || second == null) {
            throw new IllegalArgumentException("Dream pair nodes must not be null");
        }
        if (first.equals(second)) {
            throw new IllegalArgumentException("Dream self-pair is forbidden");
        }
        if (first.accessLevel() != second.accessLevel()) {
            throw new IllegalArgumentException("Dream cross-ACL pair is forbidden");
        }
        if (first.compareTo(second) >= 0) {
            throw new IllegalArgumentException("Dream pair must be in canonical order");
        }
    }

    public static DreamPair of(ChunkGraphNode left, ChunkGraphNode right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("Dream pair nodes must not be null");
        }
        return left.compareTo(right) < 0
                ? new DreamPair(left, right)
                : new DreamPair(right, left);
    }
}
