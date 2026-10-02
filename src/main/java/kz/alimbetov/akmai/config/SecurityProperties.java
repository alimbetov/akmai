package kz.alimbetov.akmai.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "akmai.security")
public record SecurityProperties(
        boolean enabled,
        String apiKey,
        boolean allowUnauthenticatedLocal,
        Set<Long> accessLevels
) {

    public SecurityProperties {
        accessLevels = accessLevels == null ? Set.of() : Set.copyOf(accessLevels);
    }
}
