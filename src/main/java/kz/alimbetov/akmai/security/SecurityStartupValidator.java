package kz.alimbetov.akmai.security;

import java.util.Arrays;
import java.util.Locale;
import kz.alimbetov.akmai.config.SecurityProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class SecurityStartupValidator implements ApplicationRunner {

    private final SecurityProperties properties;
    private final Environment environment;

    public SecurityStartupValidator(
            SecurityProperties properties,
            Environment environment
    ) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean productionProfile = Arrays.asList(
                environment.getActiveProfiles()
        ).contains("prod");
        String deploymentEnvironment = deploymentEnvironment();
        boolean local = isLocal(deploymentEnvironment) && !productionProfile;
        boolean hardened = !local;

        if (hardened && !properties.enabled()) {
            throw new IllegalStateException(
                    productionProfile
                            ? "Production profile requires API-key security"
                            : "Non-local environment requires API-key security"
            );
        }
        if (hardened && properties.allowUnauthenticatedLocal()) {
            throw new IllegalStateException(
                    productionProfile
                            ? "Production profile forbids unauthenticated local mode"
                            : "Non-local environment forbids unauthenticated local mode"
            );
        }
        if (properties.enabled()
                && (properties.apiKey() == null
                || properties.apiKey().isBlank())) {
            throw new IllegalStateException(
                    "AKMAI security is enabled but API key is missing"
            );
        }
        if (hardened
                && properties.apiKey() != null
                && properties.apiKey().length() < 32) {
            throw new IllegalStateException(
                    "Non-local API key must contain at least 32 characters"
            );
        }
        if (hardened
                && (properties.adminApiKey() == null
                || properties.adminApiKey().isBlank())) {
            throw new IllegalStateException(
                    "Non-local admin API key is required"
            );
        }
        if (hardened
                && properties.adminApiKey().length() < 32) {
            throw new IllegalStateException(
                    "Non-local admin API key must contain at least 32 characters"
            );
        }
        if (hardened
                && properties.adminApiKey().equals(properties.apiKey())) {
            throw new IllegalStateException(
                    "Non-local admin API key must differ from API key"
            );
        }
        if (properties.accessLevels().isEmpty()
                || properties.accessLevels().stream()
                .anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalStateException(
                    "AKMAI security access levels must contain positive values"
            );
        }
        if (local
                && !properties.enabled()
                && !properties.allowUnauthenticatedLocal()) {
            throw new IllegalStateException(
                    "Unauthenticated local mode was not explicitly enabled"
            );
        }
        if (hardened) {
            requireSecret("DB_USERNAME");
            requireSecret("DB_PASSWORD");
        }
    }

    private String deploymentEnvironment() {
        String explicit = environment.getProperty("AKMAI_ENVIRONMENT");
        if (explicit == null || explicit.isBlank()) {
            explicit = environment.getProperty("akmai.environment");
        }
        if (explicit == null || explicit.isBlank()) {
            return Arrays.asList(environment.getActiveProfiles()).contains("prod")
                    ? "prod"
                    : "local";
        }
        return explicit.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isLocal(String deploymentEnvironment) {
        return "local".equals(deploymentEnvironment)
                || "dev".equals(deploymentEnvironment)
                || "test".equals(deploymentEnvironment);
    }

    private void requireSecret(String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    property + " must be explicitly configured outside local development"
            );
        }
    }
}
