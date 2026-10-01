package kz.alimbetov.akmai.rag.query;

import java.util.List;

public record QueryDecompositionResult(
        List<String> units,
        int overflowCount
) {
    public QueryDecompositionResult {
        units = List.copyOf(units == null ? List.of() : units);
        if (overflowCount < 0) {
            throw new IllegalArgumentException("overflowCount must not be negative");
        }
    }
}
