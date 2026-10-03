package kz.alimbetov.akmai.knowledge.lifecycle;

public record GenerationIdentity(
        String documentId,
        long generation,
        long accessLevel
) {
    public GenerationIdentity {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException(
                    "documentId must not be blank"
            );
        }
        if (generation <= 0) {
            throw new IllegalArgumentException(
                    "generation must be positive"
            );
        }
        if (accessLevel <= 0) {
            throw new IllegalArgumentException(
                    "accessLevel must be positive"
            );
        }
    }
}
