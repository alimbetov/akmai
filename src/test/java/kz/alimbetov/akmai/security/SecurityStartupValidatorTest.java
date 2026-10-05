package kz.alimbetov.akmai.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import kz.alimbetov.akmai.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class SecurityStartupValidatorTest {

    private static final String STRONG_KEY =
            "0123456789abcdef0123456789abcdef";
    private static final String STRONG_ADMIN_KEY =
            "abcdef0123456789abcdef0123456789";

    @Test
    void productionRequiresSecurityEnabled() {
        assertThatThrownBy(() -> validator(
                new SecurityProperties(false, "", false, Set.of(1L)),
                "prod",
                "prod",
                true
        ).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Production profile requires API-key security"
                );
    }

    @Test
    void nonLocalEnvironmentRequiresSecurityWithoutProdProfile() {
        assertThatThrownBy(() -> validator(
                new SecurityProperties(false, "", false, Set.of(1L)),
                "staging",
                null,
                true
        ).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Non-local environment requires API-key security"
                );
    }

    @Test
    void nonLocalEnvironmentForbidsLocalBypass() {
        assertThatThrownBy(() -> validator(
                new SecurityProperties(
                        true,
                        STRONG_KEY,
                        STRONG_ADMIN_KEY,
                        true,
                        Set.of(1L)
                ),
                "staging",
                null,
                true
        ).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Non-local environment forbids unauthenticated local mode"
                );
    }

    @Test
    void productionForbidsUnauthenticatedLocalMode() {
        assertThatThrownBy(() -> validator(
                new SecurityProperties(
                        true,
                        STRONG_KEY,
                        true,
                        Set.of(1L)
                ),
                "prod",
                "prod",
                true
        ).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "forbids unauthenticated local mode"
                );
    }

    @Test
    void productionRejectsWeakApiKey() {
        assertThatThrownBy(() -> validator(
                new SecurityProperties(
                        true,
                        "short-secret",
                        false,
                        Set.of(1L)
                ),
                "prod",
                "prod",
                true
        ).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }

    @Test
    void productionAcceptsExplicitSecureConfiguration() {
        validator(
                new SecurityProperties(
                        true,
                        STRONG_KEY,
                        STRONG_ADMIN_KEY,
                        false,
                        Set.of(1L, 2L)
                ),
                "prod",
                "prod",
                true
        ).run(arguments());
    }

    @Test
    void productionRejectsMissingAdminKey() {
        assertThatThrownBy(() -> validator(
                new SecurityProperties(
                        true,
                        STRONG_KEY,
                        false,
                        Set.of(1L)
                ),
                "prod",
                "prod",
                true
        ).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin API key is required");
    }

    @Test
    void productionRequiresDistinctAdminKey() {
        assertThatThrownBy(() -> validator(
                new SecurityProperties(
                        true,
                        STRONG_KEY,
                        STRONG_KEY,
                        false,
                        Set.of(1L)
                ),
                "prod",
                "prod",
                true
        ).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void nonLocalRequiresExplicitDatabaseCredentials() {
        assertThatThrownBy(() -> validator(
                new SecurityProperties(
                        true,
                        STRONG_KEY,
                        STRONG_ADMIN_KEY,
                        false,
                        Set.of(1L)
                ),
                "staging",
                null,
                false
        ).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_USERNAME must be explicitly configured");
    }

    @Test
    void localDevelopmentStillAllowsExplicitUnauthenticatedMode() {
        validator(
                new SecurityProperties(false, "", true, Set.of(1L)),
                "local",
                null,
                false
        ).run(arguments());
    }

    @Test
    void localDevelopmentStillRequiresExplicitOptInWhenDisabled() {
        assertThatThrownBy(() -> validator(
                new SecurityProperties(false, "", false, Set.of(1L)),
                "local",
                null,
                false
        ).run(arguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Unauthenticated local mode was not explicitly enabled"
                );
    }

    private SecurityStartupValidator validator(
            SecurityProperties properties,
            String deployment,
            String profile,
            boolean databaseCredentials
    ) {
        MockEnvironment environment = new MockEnvironment();
        if (deployment != null) {
            environment.setProperty("AKMAI_ENVIRONMENT", deployment);
        }
        if (profile != null) {
            environment.setActiveProfiles(profile);
        }
        if (databaseCredentials) {
            environment.setProperty("DB_USERNAME", "runtime-user");
            environment.setProperty("DB_PASSWORD", "runtime-secret");
        }
        return new SecurityStartupValidator(properties, environment);
    }

    private DefaultApplicationArguments arguments() {
        return new DefaultApplicationArguments(new String[0]);
    }
}
