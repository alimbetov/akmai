package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;

public record SemanticConceptSurface(
        String conceptId,
        String domainId,
        String subdomainId,
        String language,
        String preferredPhrase,
        String lemmaPhrase,
        List<String> aliases,
        List<String> stemTokens
) {
    public SemanticConceptSurface {
        if (conceptId == null || conceptId.isBlank()) {
            throw new IllegalArgumentException("conceptId is required");
        }
        if (domainId == null || domainId.isBlank()) {
            throw new IllegalArgumentException("domainId is required");
        }
        if (subdomainId == null || subdomainId.isBlank()) {
            throw new IllegalArgumentException("subdomainId is required");
        }
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language is required");
        }
        if (preferredPhrase == null || preferredPhrase.isBlank()) {
            throw new IllegalArgumentException("preferredPhrase is required");
        }
        if (lemmaPhrase == null || lemmaPhrase.isBlank()) {
            throw new IllegalArgumentException("lemmaPhrase is required");
        }
        aliases = List.copyOf(aliases == null ? List.of() : aliases);
        stemTokens = List.copyOf(
                stemTokens == null ? List.of() : stemTokens
        );
    }
}
