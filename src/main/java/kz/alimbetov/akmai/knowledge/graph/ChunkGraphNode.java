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
                + ":"
                + documentId.length()
                + ":"
                + documentId
                + ":"
                + generation
                + ":"
                + chunkId.length()
                + ":"
                + chunkId;
    }

    @Override
    public int compareTo(ChunkGraphNode other) {
        int access = Long.compare(accessLevel, other.accessLevel);
        if (access != 0) {
            return access;
        }
        int document = documentId.compareTo(other.documentId);
        if (document != 0) {
            return document;
        }
        int generationOrder = Long.compare(generation, other.generation);
        if (generationOrder != 0) {
            return generationOrder;
        }
        return chunkId.compareTo(other.chunkId);
    }
}
