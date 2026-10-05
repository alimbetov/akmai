package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;
import java.util.Map;

public record SemanticConceptAliasDefinition(
        String version,
        List<Entry> entries
) {
    public SemanticConceptAliasDefinition {
        entries = List.copyOf(entries == null ? List.of() : entries);
    }

    public record Entry(
            String conceptId,
            Map<String, List<String>> aliases
    ) {
        public Entry {
            aliases = Map.copyOf(aliases == null ? Map.of() : aliases);
        }
    }
}
