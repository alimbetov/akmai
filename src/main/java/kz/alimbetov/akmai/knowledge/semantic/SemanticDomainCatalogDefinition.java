package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;

public record SemanticDomainCatalogDefinition(
        String version,
        List<SemanticDomainDefinition> domains
) {
    public SemanticDomainCatalogDefinition {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("semantic catalog version is required");
        }
        domains = List.copyOf(domains == null ? List.of() : domains);
    }
}
