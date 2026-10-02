package kz.alimbetov.akmai.rag.retrieval;

import java.util.List;
import java.util.Map;

public record RetrievalExecutionResult(
        List<RetrievalHit> hits,
        Map<String, RetrievalStepOutcome> outcomes,
        boolean degraded,
        boolean criticalFailure
) {
    public RetrievalExecutionResult {
        hits = List.copyOf(hits == null ? List.of() : hits);
        outcomes = Map.copyOf(outcomes == null ? Map.of() : outcomes);
    }
}
