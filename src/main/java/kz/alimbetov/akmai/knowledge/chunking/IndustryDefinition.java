package kz.alimbetov.akmai.knowledge.chunking;

import java.util.List;
import java.util.Map;

public record IndustryDefinition(
        String id,
        IndustryDomain domain,
        Map<String, String> names,
        Map<String, List<HeadingRule>> headings,
        Map<String, Map<String, List<String>>> semanticTypes
) {

    public IndustryDefinition {
        names = Map.copyOf(names == null ? Map.of() : names);
        headings = Map.copyOf(headings == null ? Map.of() : headings);
        semanticTypes = Map.copyOf(
                semanticTypes == null ? Map.of() : semanticTypes
        );
    }

    public record HeadingRule(
            int level,
            String pattern
    ) {
        public HeadingRule {
            if (level < 1 || level > 7) {
                throw new IllegalArgumentException(
                        "heading level must be between 1 and 7"
                );
            }
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalArgumentException(
                        "heading pattern must not be blank"
                );
            }
        }
    }
}
