package kz.alimbetov.akmai.security;

import java.util.Arrays;
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
        boolean production = Arrays.asList(
                environment.getActiveProfiles()
        ).contains("prod");

        if (production && !properties.enabled()) {
            throw new IllegalStateException(
                    "Production profile requires API-key security"
            );
        }
        if (properties.enabled()
                && (properties.apiKey() == null
                || properties.apiKey().isBlank())) {
            throw new IllegalStateException(
                    "AKMAI security is enabled but API key is missing"
            );
        }
        if (properties.accessLevels().isEmpty()
                || properties.accessLevels().stream()
                .anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalStateException(
                    "AKMAI security access levels must contain positive values"
            );
        }
        if (!production
                && !properties.enabled()
                && !properties.allowUnauthenticatedLocal()) {
            throw new IllegalStateException(
                    "Unauthenticated local mode was not explicitly enabled"
            );
        }
    }
}
