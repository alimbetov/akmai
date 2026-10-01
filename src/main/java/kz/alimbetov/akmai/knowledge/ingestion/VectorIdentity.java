package kz.alimbetov.akmai.knowledge.ingestion;

public final class VectorIdentity {

    private VectorIdentity() {
    }

    public static String physicalId(
            String documentId,
            long generation,
            String chunkId
    ) {
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be > 0");
        }
        return documentId + "::g" + generation + "::" + chunkId;
    }
}
