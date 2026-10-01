package kz.alimbetov.akmai.knowledge.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;

public record AddKnowledgeRequest(
        @NotBlank String documentId,
        @NotBlank String title,
        @NotBlank String text,
        @NotBlank String source,
        @NotBlank String language,
        @NotNull KnowledgeDomain domain,
        Map<String, Object> metadata
) {
}
