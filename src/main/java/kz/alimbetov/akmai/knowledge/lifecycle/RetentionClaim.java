package kz.alimbetov.akmai.knowledge.lifecycle;

public record RetentionClaim(
        String documentId,
        long generation
) {
}
