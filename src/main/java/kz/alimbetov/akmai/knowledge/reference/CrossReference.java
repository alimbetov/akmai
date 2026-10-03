package kz.alimbetov.akmai.knowledge.reference;

public record CrossReference(
        CrossReferenceType type,
        String canonicalValue,
        String rawValue,
        String language,
        ReferenceTargetScope targetScope,
        String targetDocumentId,
        int startOffset,
        int endOffset
) {

    public CrossReference(
            CrossReferenceType type,
            String canonicalValue,
            String rawValue,
            String language,
            ReferenceTargetScope targetScope,
            String targetDocumentId
    ) {
        this(
                type,
                canonicalValue,
                rawValue,
                language,
                targetScope,
                targetDocumentId,
                -1,
                -1
        );
    }
}
