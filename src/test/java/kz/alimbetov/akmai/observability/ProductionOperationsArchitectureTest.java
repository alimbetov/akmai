package kz.alimbetov.akmai.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProductionOperationsArchitectureTest {

    @Test
    void runtimeIncludesPrometheusAndOpenTelemetrySupport()
            throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        String application = Files.readString(Path.of(
                "src/main/resources/application.yml"
        ));
        String production = Files.readString(Path.of(
                "src/main/resources/application-prod.yml"
        ));

        assertThat(pom)
                .contains("micrometer-registry-prometheus")
                .contains("micrometer-tracing-bridge-otel")
                .contains("opentelemetry-exporter-otlp");

        assertThat(application)
                .contains("health,info,metrics,prometheus")
                .contains("percentiles-histogram")
                .contains("akmai.retrieval.strategy");

        assertThat(production)
                .contains("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
                .contains("AKMAI_OTLP_TRACING_ENABLED")
                .contains("deployment.environment");
    }

    @Test
    void productionOperationalArtifactsAreVersioned() throws Exception {
        assertThat(Path.of("Dockerfile")).exists();
        assertThat(Path.of("ops/prometheus/prometheus.yml")).exists();
        assertThat(Path.of("ops/prometheus/akmai-alerts.yml")).exists();
        assertThat(Path.of(
                "ops/grafana/akmai-production-overview.json"
        )).exists();
        assertThat(Path.of("ops/otel/collector-config.yaml")).exists();
        assertThat(Path.of(
                "docs/operations/disaster-recovery-rebuild.md"
        )).exists();
        assertThat(Path.of(
                "docs/operations/dr-post-rebuild-verification.sql"
        )).exists();
    }

    @Test
    void alertsCoverCapacityAndDataHealthFailureModes()
            throws Exception {
        String alerts = Files.readString(Path.of(
                "ops/prometheus/akmai-alerts.yml"
        ));

        assertThat(alerts)
                .contains("AkmaiRetentionBacklogHigh")
                .contains("AkmaiExecutorRejectedWork")
                .contains("AkmaiRetrievalTimeouts")
                .contains("AkmaiRetrievalRejected")
                .contains("AkmaiRetrievalDeadRowRatioHigh")
                .contains("AkmaiDatabasePoolPending");
    }

    @Test
    void containerRunsAsNonRootAndFailsOnJvmOom()
            throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertThat(dockerfile)
                .contains("USER 10001")
                .contains("-XX:+ExitOnOutOfMemoryError")
                .contains("eclipse-temurin:21-jre");
    }
}
