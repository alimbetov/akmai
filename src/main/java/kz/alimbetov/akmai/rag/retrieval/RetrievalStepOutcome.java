package kz.alimbetov.akmai.rag.retrieval;

import java.util.List;

public record RetrievalStepOutcome(
        String stepId,
        RetrievalType type,
        RetrievalOutcomeStatus status,
        List<RetrievalHit> hits,
        String failureCategory
) {
    public RetrievalStepOutcome {
        hits = List.copyOf(hits == null ? List.of() : hits);
    }

    public boolean successful() {
        return status == RetrievalOutcomeStatus.SUCCESS
                || status == RetrievalOutcomeStatus.EMPTY;
    }
}
