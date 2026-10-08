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

    private static final String DEFAULT_DATABASE_CREDENTIAL = "akmai";

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
        String[] activeProfiles = environment.getActiveProfiles();
        boolean productionProfile = Arrays.asList(activeProfiles).contains("prod");
        String deploymentEnvironment = deploymentEnvironment(activeProfiles);
        boolean nonLocalProfile = Arrays.stream(activeProfiles)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(value -> !isLocal(value));
        boolean local = deploymentEnvironment != null
                && isLocal(deploymentEnvironment)
                && !productionProfile
                && !nonLocalProfile;
        boolean hardened = !local;

        if (hardened && !properties.enabled()) {
            throw new IllegalStateException(
                    productionProfile
                            ? "Production profile requires API-key security"
                            : "Non-local or unspecified environment requires API-key security"
            );
        }
        if (hardened && properties.allowUnauthenticatedLocal()) {
            throw new IllegalStateException(
                    productionProfile
                            ? "Production profile forbids unauthenticated local mode"
                            : "Non-local or unspecified environment forbids unauthenticated local mode"
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
            validateDatabaseCredentials();
        }
    }

    private String deploymentEnvironment(String[] activeProfiles) {
        String explicit = environment.getProperty("AKMAI_ENVIRONMENT");
        if (explicit == null || explicit.isBlank()) {
            explicit = environment.getProperty("akmai.environment");
        }
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim().toLowerCase(Locale.ROOT);
        }
        return Arrays.stream(activeProfiles)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private boolean isLocal(String deploymentEnvironment) {
        return "local".equals(deploymentEnvironment)
                || "dev".equals(deploymentEnvironment)
                || "test".equals(deploymentEnvironment);
    }

    private void validateDatabaseCredentials() {
        String username = firstNonBlank(
                environment.getProperty("DB_USERNAME"),
                environment.getProperty("SPRING_DATASOURCE_USERNAME"),
                environment.getProperty("spring.datasource.username")
        );
        String password = firstNonBlank(
                environment.getProperty("DB_PASSWORD"),
                environment.getProperty("SPRING_DATASOURCE_PASSWORD"),
                environment.getProperty("spring.datasource.password")
        );
        if (username == null) {
            throw new IllegalStateException(
                    "Database username must be explicitly configured outside local development"
            );
        }
        if (password == null) {
            throw new IllegalStateException(
                    "Database password must be explicitly configured outside local development"
            );
        }
        if (DEFAULT_DATABASE_CREDENTIAL.equals(username)
                || DEFAULT_DATABASE_CREDENTIAL.equals(password)) {
            throw new IllegalStateException(
                    "Default datasource credentials are forbidden outside local development"
            );
        }
    }

    private String firstNonBlank(String... values) {
        return Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }
}
