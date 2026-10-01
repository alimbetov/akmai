package kz.alimbetov.akmai.knowledge.model;

import java.util.Map;

public record KnowledgeDocument(
        String documentId,
        String title,
        String rawText,
        String language,
        KnowledgeDomain domain,
        Map<String, Object> metadata
) {
}
