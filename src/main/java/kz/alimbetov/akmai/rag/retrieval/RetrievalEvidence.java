package kz.alimbetov.akmai.rag.retrieval;

public record RetrievalEvidence(
        RetrievalType type,
        int rank,
        Double rawScore
) {
    public RetrievalEvidence {
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be >= 1");
        }
    }
}
