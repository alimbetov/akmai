package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;
import java.util.Map;

public record SemanticDomainDefinition(
        String id,
        SemanticDomainKind kind,
        Map<String, String> names,
        Map<String, List<String>> anchors
) {
    public SemanticDomainDefinition {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException(
                    "semantic domain id must be a stable lowercase identifier"
            );
        }
        if (kind == null) {
            throw new IllegalArgumentException("semantic domain kind is required");
        }
        names = Map.copyOf(names == null ? Map.of() : names);
        anchors = anchors == null
                ? Map.of()
                : anchors.entrySet().stream().collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> List.copyOf(entry.getValue())
                        )
                );
    }
}
