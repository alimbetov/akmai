package kz.alimbetov.akmai.knowledge.graph;

/**
 * Canonical ordering and validation for undirected adaptive-graph pairs.
 */
public final class GraphPairCanonicalizer {

    private GraphPairCanonicalizer() {
    }

    public static CanonicalPair canonicalize(
            ChunkGraphNode left,
            ChunkGraphNode right
    ) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("graph pair nodes must not be null");
        }
        if (left.equals(right)) {
            throw new IllegalArgumentException("self graph association is not allowed");
        }
        if (left.accessLevel() != right.accessLevel()) {
            throw new IllegalArgumentException("cross-ACL graph association is forbidden");
        }
        return left.compareTo(right) <= 0
                ? new CanonicalPair(left, right)
                : new CanonicalPair(right, left);
    }

    public record CanonicalPair(
            ChunkGraphNode first,
            ChunkGraphNode second
    ) {
    }
}
