package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;

public record SemanticAmbiguityDefinition(
        String version,
        List<Entry> entries
) {
    public SemanticAmbiguityDefinition {
        entries = List.copyOf(entries == null ? List.of() : entries);
    }

    public record Entry(
            String surface,
            List<String> conceptIds,
            String rationale
    ) {
        public Entry {
            conceptIds = List.copyOf(
                    conceptIds == null ? List.of() : conceptIds
            );
        }
    }
}
