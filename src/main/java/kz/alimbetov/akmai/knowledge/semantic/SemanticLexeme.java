package kz.alimbetov.akmai.knowledge.semantic;

public record SemanticLexeme(
        String domainId,
        String language,
        String surface,
        double weight,
        boolean anchor
) {
}
