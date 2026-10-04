package kz.alimbetov.akmai.runtimeconfig;

import java.time.Instant;

public record AppParameter(
        String key,
        AppParameterType type,
        String value,
        long version,
        Instant updatedAt,
        String updatedBy
) {

    public AppParameter {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "app parameter key must not be blank"
            );
        }
        if (type == null) {
            throw new IllegalArgumentException(
                    "app parameter type must not be null"
            );
        }
        if (value == null) {
            throw new IllegalArgumentException(
                    "app parameter value must not be null"
            );
        }
        if (version < 0) {
            throw new IllegalArgumentException(
                    "app parameter version must not be negative"
            );
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException(
                    "app parameter updatedAt must not be null"
            );
        }
        if (updatedBy == null || updatedBy.isBlank()) {
            throw new IllegalArgumentException(
                    "app parameter updatedBy must not be blank"
            );
        }
    }

    public boolean booleanValue() {
        if (type != AppParameterType.BOOLEAN) {
            throw new IllegalStateException(
                    "Parameter " + key + " is not BOOLEAN"
            );
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalStateException(
                "Invalid BOOLEAN value for " + key
        );
    }
}
