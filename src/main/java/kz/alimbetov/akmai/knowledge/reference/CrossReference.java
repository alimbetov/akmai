package kz.alimbetov.akmai.knowledge.reference;

public record CrossReference(
        CrossReferenceType type,
        String canonicalValue,
        String rawValue,
        String language,
        ReferenceTargetScope targetScope,
        String targetDocumentId
) {
}
