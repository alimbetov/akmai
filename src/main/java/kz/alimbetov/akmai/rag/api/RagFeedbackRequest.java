package kz.alimbetov.akmai.rag.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Set;
import kz.alimbetov.akmai.rag.learning.RagFeedbackReason;

public record RagFeedbackRequest(
        @NotBlank String requestId,
        @NotNull RagFeedbackReason reason,
        @Size(max = 2000) String details,
        @NotEmpty @Size(max = 256)
        Set<@Valid @NotNull @Positive Long> accessLevels
) {
    public RagFeedbackRequest {
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException("accessLevels must not be empty");
        }
        if (accessLevels.stream().anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalArgumentException(
                    "accessLevels must contain only positive values"
            );
        }
        accessLevels = Set.copyOf(accessLevels);
        details = details == null ? null : details.trim();
    }
}
