package kz.alimbetov.akmai.runtimeconfig;

import java.time.Instant;

public record ResolvedAppParameter(
        AppParameterKey key,
        boolean value,
        Long version,
        Instant updatedAt,
        String updatedBy,
        AppParameterSource source
) {

    public ResolvedAppParameter {
        if (key == null || source == null) {
            throw new IllegalArgumentException(
                    "resolved app parameter key/source must not be null"
            );
        }
        if (source == AppParameterSource.DATABASE
                && (version == null
                || updatedAt == null
                || updatedBy == null
                || updatedBy.isBlank())) {
            throw new IllegalArgumentException(
                    "database app parameter requires version and audit fields"
            );
        }
    }

    public static ResolvedAppParameter from(AppParameter parameter) {
        AppParameterKey key = AppParameterKey.parse(
                parameter.key()
        );
        return new ResolvedAppParameter(
                key,
                parameter.booleanValue(),
                parameter.version(),
                parameter.updatedAt(),
                parameter.updatedBy(),
                AppParameterSource.DATABASE
        );
    }

    public static ResolvedAppParameter fallback(
            AppParameterKey key,
            boolean value
    ) {
        return new ResolvedAppParameter(
                key,
                value,
                null,
                null,
                null,
                AppParameterSource.STATIC_FALLBACK
        );
    }
}
