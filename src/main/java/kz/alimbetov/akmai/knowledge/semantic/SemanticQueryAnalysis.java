package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;

public record SemanticQueryAnalysis(
        String detectedLanguage,
        String semanticLanguage,
        double confidence,
        List<String> domains,
        List<SemanticConceptMatch> concepts
) {
    public SemanticQueryAnalysis {
        domains = List.copyOf(domains == null ? List.of() : domains);
        concepts = List.copyOf(
                concepts == null ? List.of() : concepts
        );
    }

    public boolean hasConcepts() {
        return !concepts.isEmpty();
    }
}
