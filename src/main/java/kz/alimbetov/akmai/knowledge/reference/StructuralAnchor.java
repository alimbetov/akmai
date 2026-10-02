package kz.alimbetov.akmai.knowledge.reference;

public record StructuralAnchor(
        CrossReferenceType type,
        String canonicalValue,
        String rawValue,
        String language
) {
}
