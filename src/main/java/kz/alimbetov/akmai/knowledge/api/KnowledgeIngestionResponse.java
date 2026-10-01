package kz.alimbetov.akmai.knowledge.api;

public record KnowledgeIngestionResponse(
        String documentId,
        int chunkCount
) {
}
