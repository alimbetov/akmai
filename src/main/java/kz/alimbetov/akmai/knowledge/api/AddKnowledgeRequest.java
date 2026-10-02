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

    /**
     * Compatibility constructor for internal callers created before access
     * scoping. HTTP deserialization uses the canonical constructor, where
     * accessLevel is mandatory.
     */
    @Deprecated(forRemoval = true)
    public AddKnowledgeRequest(
            String documentId,
            String title,
            String text,
            String source,
            String language,
            KnowledgeDomain domain,
            Map<String, Object> metadata
    ) {
        this(documentId, title, text, source, language, domain, null, metadata);
        throw new UnsupportedOperationException(
                "accessLevel is required"
        );
    }
}
