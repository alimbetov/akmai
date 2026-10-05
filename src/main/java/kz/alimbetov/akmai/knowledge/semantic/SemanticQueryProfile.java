package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;

public record SemanticQueryProfile(
        String ontologyVersion,
        String language,
        List<SemanticDomainScore> domains
) {
    public SemanticQueryProfile {
        domains = List.copyOf(domains == null ? List.of() : domains);
    }
}
