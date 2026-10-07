package kz.alimbetov.akmai.rag.query;

public record SemanticQueryMemoryNamespace(
        String embeddingProfileId,
        String retrievalPolicyVersion,
        String learningPolicyVersion,
        String groundingPolicyVersion
) {
    public static final String LEGACY_UNSCOPED = "legacy-unscoped";

    public SemanticQueryMemoryNamespace {
        embeddingProfileId = requireIdentity(embeddingProfileId, "embeddingProfileId");
        retrievalPolicyVersion = requireIdentity(
                retrievalPolicyVersion,
                "retrievalPolicyVersion"
        );
        learningPolicyVersion = requireIdentity(
                learningPolicyVersion,
                "learningPolicyVersion"
        );
        groundingPolicyVersion = requireIdentity(
                groundingPolicyVersion,
                "groundingPolicyVersion"
        );
    }

    public static SemanticQueryMemoryNamespace legacy(String embeddingProfileId) {
        return new SemanticQueryMemoryNamespace(
                embeddingProfileId,
                LEGACY_UNSCOPED,
                LEGACY_UNSCOPED,
                LEGACY_UNSCOPED
        );
    }

    private static String requireIdentity(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
