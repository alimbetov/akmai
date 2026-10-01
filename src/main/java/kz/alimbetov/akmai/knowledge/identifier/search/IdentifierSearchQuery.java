package kz.alimbetov.akmai.knowledge.identifier.search;

import kz.alimbetov.akmai.knowledge.identifier.IdentifierType;

public record IdentifierSearchQuery(
        IdentifierType type,
        String normalizedValue,
        MatchMode matchMode,
        int limit
) {
}
