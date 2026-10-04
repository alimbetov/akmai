package kz.alimbetov.akmai.runtimeconfig;

import java.time.Instant;

public record AppParameterResponse(
        String key,
        String description,
        String type,
        boolean value,
        Long version,
        Instant updatedAt,
        String updatedBy,
        String source
) {

    public static AppParameterResponse from(
            ResolvedAppParameter parameter
    ) {
        return new AppParameterResponse(
                parameter.key().key(),
                parameter.key().description(),
                AppParameterType.BOOLEAN.name(),
                parameter.value(),
                parameter.version(),
                parameter.updatedAt(),
                parameter.updatedBy(),
                parameter.source().name()
        );
    }
}
