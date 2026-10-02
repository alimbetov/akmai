package kz.alimbetov.akmai.knowledge.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;

public record AddKnowledgeRequest(
        @NotBlank String documentId,
        @NotBlank String title,
        @NotBlank String text,
        @NotBlank String source,
        @NotBlank String language,
        @NotNull KnowledgeDomain domain,
        @NotNull @Positive Long accessLevel,
        Map<String, Object> metadata
) {

    public AddKnowledgeRequest {
        if (accessLevel == null || accessLevel <= 0) {
            throw new IllegalArgumentException(
                    "accessLevel must be positive"
            );
        }
    }
}
