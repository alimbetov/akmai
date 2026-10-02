package kz.alimbetov.akmai.rag.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Set;

public record QuestionRequest(
        @NotBlank String question,
        @NotNull @Size(max = 256) Set<@Valid @NotNull @Positive Long> accessLevels
) {

    public QuestionRequest {
        accessLevels = accessLevels == null ? null : Set.copyOf(accessLevels);
    }

    @Deprecated(forRemoval = false)
    public QuestionRequest(String question) {
        this(question, Set.of());
    }
}
