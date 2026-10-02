package kz.alimbetov.akmai.rag.retrieval;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public record RetrievalContext(
        List<RetrievalHit> dependencyHits,
        Set<Long> accessLevels
) {

    public RetrievalContext {
        dependencyHits = List.copyOf(
                dependencyHits == null ? List.of() : dependencyHits
        );
        if (accessLevels == null || accessLevels.isEmpty()) {
            accessLevels = Set.of();
        } else {
            TreeSet<Long> normalized = new TreeSet<>();
            for (Long value : accessLevels) {
                if (value == null || value <= 0) {
                    throw new IllegalArgumentException(
                            "accessLevels must contain only positive values"
                    );
                }
                normalized.add(value);
            }
            accessLevels = Set.copyOf(normalized);
        }
    }

    public RetrievalContext(List<RetrievalHit> dependencyHits) {
        this(dependencyHits, Set.of());
    }

    public static RetrievalContext empty() {
        return new RetrievalContext(List.of(), Set.of());
    }

    public Set<String> documentIds() {
        return dependencyHits.stream()
                .map(RetrievalHit::documentId)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
