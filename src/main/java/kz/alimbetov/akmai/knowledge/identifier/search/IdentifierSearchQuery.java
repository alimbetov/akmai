package kz.alimbetov.akmai.knowledge.identifier.search;

import java.util.Set;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierType;

public record IdentifierSearchQuery(
        IdentifierType type,
        String normalizedValue,
        MatchMode matchMode,
        int limit,
        Set<Long> accessLevels
) {

    public IdentifierSearchQuery {
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    "accessLevels must not be empty"
            );
        }
        if (accessLevels.stream().anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalArgumentException(
                    "accessLevels must contain only positive values"
            );
        }
        accessLevels = Set.copyOf(accessLevels);
    }
}
