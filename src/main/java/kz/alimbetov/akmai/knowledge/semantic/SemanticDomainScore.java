package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;

public record SemanticDomainScore(
        String domainId,
        SemanticDomainKind kind,
        double score,
        List<String> matchedAnchors
) {
    public SemanticDomainScore {
        matchedAnchors = List.copyOf(
                matchedAnchors == null ? List.of() : matchedAnchors
        );
    }
}
