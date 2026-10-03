package kz.alimbetov.akmai.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SecurityDeliveryArchitectureTest {

    @Test
    void sensitiveManagementEndpointsAreProtectedFailClosed()
            throws Exception {
        String configuration = Files.readString(Path.of(
                "src/main/java/kz/alimbetov/akmai/security/"
                        + "ApiSecurityConfiguration.java"
        ));
        String filter = Files.readString(Path.of(
                "src/main/java/kz/alimbetov/akmai/security/"
                        + "ApiKeyAuthenticationFilter.java"
        ));

        assertThat(configuration)
                .contains("/actuator/health/**")
                .contains("/actuator/metrics")
                .contains("/actuator/info")
                .contains("requestMatchers(\"/actuator/**\").denyAll()");

        assertThat(filter)
                .contains("/actuator/metrics")
                .contains("/actuator/info")
                .contains("requiresApiKey");
    }

    @Test
    void productionProfileCannotFallBackToLocalUnauthenticatedMode()
            throws Exception {
        String profile = Files.readString(Path.of(
                "src/main/resources/application-prod.yml"
        ));

        assertThat(profile)
                .contains("on-profile: prod")
                .contains("enabled: true")
                .contains("api-key: ${AKMAI_SECURITY_API_KEY}")
                .contains("allow-unauthenticated-local: false");
    }

    @Test
    void pullRequestsHaveStableRetrievalRequiredChecks()
            throws Exception {
        String storageGate = Files.readString(Path.of(
                ".github/workflows/"
                        + "retrieval-storage-decision-matrix.yml"
        ));
        String qualityGate = Files.readString(Path.of(
                ".github/workflows/retrieval-quality.yml"
        ));

        assertThat(storageGate)
                .contains("pull_request:")
                .contains("retrieval-gate:")
                .contains("Required retrieval benchmark gate")
                .contains("retrieval_sensitive");

        assertThat(qualityGate)
                .contains("pull_request:")
                .contains("Retrieval Quality Gate")
                .contains("MultilingualRetrievalQualityRegressionTest");
    }
}
