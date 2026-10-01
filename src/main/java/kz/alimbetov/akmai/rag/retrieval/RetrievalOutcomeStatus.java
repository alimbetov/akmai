package kz.alimbetov.akmai.rag.retrieval;

public enum RetrievalOutcomeStatus {
    SUCCESS,
    EMPTY,
    FAILED,
    TIMED_OUT,
    REJECTED,
    SKIPPED_DEPENDENCY
}
