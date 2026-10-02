package kz.alimbetov.akmai.security;

import java.util.Set;

public record ApiKeyPrincipal(
        String name,
        Set<Long> accessLevels
) {

    public ApiKeyPrincipal {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("principal name must not be blank");
        }
        accessLevels = accessLevels == null ? Set.of() : Set.copyOf(accessLevels);
        if (accessLevels.stream().anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalArgumentException(
                    "principal access levels must contain only positive values"
            );
        }
    }
}
