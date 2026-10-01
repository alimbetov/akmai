package kz.alimbetov.akmai.rag.retrieval.plan;

import java.util.List;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;

public record RetrievalStep(
        String id,
        QueryChunk queryChunk,
        RetrievalType type,
        List<String> dependsOn
) {
    public boolean root() {
        return dependsOn == null || dependsOn.isEmpty();
    }
}
