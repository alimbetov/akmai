package kz.alimbetov.akmai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "akmai.security")
public record SecurityProperties(
        boolean enabled,
        String apiKey,
        boolean allowUnauthenticatedLocal
) {
}
