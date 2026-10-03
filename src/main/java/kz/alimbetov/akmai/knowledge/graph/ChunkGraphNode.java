package kz.alimbetov.akmai.knowledge.graph;

public record ChunkGraphNode(
        long accessLevel,
        String documentId,
        long generation,
        String chunkId
) implements Comparable<ChunkGraphNode> {

    public ChunkGraphNode {
        if (accessLevel <= 0) {
            throw new IllegalArgumentException("accessLevel must be positive");
        }
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId must not be blank");
        }
    }

    public String lockKey() {
        return accessLevel
                + "\u0000"
                + documentId
                + "\u0000"
                + generation
                + "\u0000"
                + chunkId;
    }

    @Override
    public int compareTo(ChunkGraphNode other) {
        return lockKey().compareTo(other.lockKey());
    }
}
