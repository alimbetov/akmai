package kz.alimbetov.akmai.knowledge.api;

public record AsyncIngestionRequest(
        int schemaVersion,
        String eventId,
        String requestId,
        String documentId,
        long accessLevel,
        CanonicalKnowledgeDocument canonicalDocument
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
