package kz.alimbetov.akmai.knowledge.identifier;

public record DetectedIdentifier(
        IdentifierType type,
        String rawValue,
        String normalizedValue,
        String contextText
) {
}
