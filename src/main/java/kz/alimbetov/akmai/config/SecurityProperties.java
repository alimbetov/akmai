package kz.alimbetov.akmai.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "akmai.security")
public record SecurityProperties(
        boolean enabled,
        String apiKey,
        String adminApiKey,
        boolean allowUnauthenticatedLocal,
        Set<Long> accessLevels
) {

    public SecurityProperties(
            boolean enabled,
            String apiKey,
            boolean allowUnauthenticatedLocal,
            Set<Long> accessLevels
    ) {
        this(
                enabled,
                apiKey,
                null,
                allowUnauthenticatedLocal,
                accessLevels
        );
    }

    public SecurityProperties {
        accessLevels = accessLevels == null
                ? Set.of()
                : Set.copyOf(accessLevels);
    }
}
