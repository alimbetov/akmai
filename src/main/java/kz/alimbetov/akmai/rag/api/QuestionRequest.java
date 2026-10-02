package kz.alimbetov.akmai.rag.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Set;

public record QuestionRequest(
        @NotBlank String question,
        @NotEmpty @Size(max = 256)
        Set<@Valid @NotNull @Positive Long> accessLevels
) {

    public QuestionRequest {
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    "accessLevels must not be empty"
            );
        }
        if (accessLevels.stream().anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalArgumentException(
                    "accessLevels must contain only positive values"
            );
        }
        accessLevels = Set.copyOf(accessLevels);
    }
}
