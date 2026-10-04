package kz.alimbetov.akmai.knowledge.semantic;

public record SemanticConceptMatch(
        String conceptId,
        String domainId,
        String subdomainId,
        String phrase,
        double weight
) {
}
