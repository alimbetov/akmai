package kz.alimbetov.akmai.runtimeconfig;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record AppParameterUpdateRequest(
        @NotNull Boolean value,
        @NotNull @PositiveOrZero Long expectedVersion
) {
}
