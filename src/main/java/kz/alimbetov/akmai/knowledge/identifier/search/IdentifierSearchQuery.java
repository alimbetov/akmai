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
        accessLevels = accessLevels == null
                ? Set.of()
                : Set.copyOf(accessLevels);
    }

    public IdentifierSearchQuery(
            IdentifierType type,
            String normalizedValue,
            MatchMode matchMode,
            int limit
    ) {
        this(type, normalizedValue, matchMode, limit, Set.of());
    }
}
