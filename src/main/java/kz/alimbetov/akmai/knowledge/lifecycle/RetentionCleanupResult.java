package kz.alimbetov.akmai.knowledge.lifecycle;

public record RetentionCleanupResult(
        String documentId,
        long generation,
        int deletedChunks,
        Status status
) {

    public enum Status {
        DELETED,
        STALE_CLAIM,
        FAILED
    }
}
