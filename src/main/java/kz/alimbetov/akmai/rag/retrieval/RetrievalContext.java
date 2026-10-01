package kz.alimbetov.akmai.rag.retrieval;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record RetrievalContext(List<RetrievalHit> dependencyHits) {

    public static RetrievalContext empty() {
        return new RetrievalContext(List.of());
    }

    public Set<String> documentIds() {
        return dependencyHits.stream()
                .map(RetrievalHit::documentId)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
