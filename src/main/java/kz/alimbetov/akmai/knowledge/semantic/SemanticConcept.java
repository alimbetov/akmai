package kz.alimbetov.akmai.knowledge.semantic;

public record SemanticConcept(
        String id,
        String domainId,
        String subdomainId,
        String preferredPhrase
) {
    public SemanticConcept {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("semantic concept id is required");
        }
        if (domainId == null || domainId.isBlank()) {
            throw new IllegalArgumentException("semantic concept domain is required");
        }
        if (subdomainId == null || subdomainId.isBlank()) {
            throw new IllegalArgumentException("semantic concept subdomain is required");
        }
        if (preferredPhrase == null || preferredPhrase.isBlank()) {
            throw new IllegalArgumentException("semantic concept phrase is required");
        }
    }
}
