package kz.alimbetov.akmai.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AkmaiMetricsAdaptiveGraphTest {

    @Test
    void recordsBoundedAdaptiveGraphMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AkmaiMetrics metrics = new AkmaiMetrics(registry);

        metrics.adaptiveGraphEdges(10, 8, 4, 2);
        metrics.adaptiveGraphMaintenanceBacklog(6);
        metrics.adaptiveGraphLearning("citation", "accepted", 3);
        metrics.adaptiveGraphLookup("hot", Duration.ofMillis(12), 5);
        metrics.adaptiveGraphExpansion("accepted", 2);
        metrics.adaptiveGraphBandTransition("warm", "hot", 1);
        metrics.adaptiveGraphMaintenance(
                "success",
                Duration.ofMillis(20),
                100,
                12
        );

        assertThat(registry.get("akmai.adaptive.graph.edges")
                .tag("band", "hot")
                .gauge()
                .value()).isEqualTo(4.0);
        assertThat(registry.get("akmai.adaptive.graph.maintenance.backlog")
                .gauge()
                .value()).isEqualTo(6.0);
        assertThat(registry.get("akmai.adaptive.graph.learning")
                .tag("signal", "citation")
                .tag("outcome", "accepted")
                .counter()
                .count()).isEqualTo(3.0);
        assertThat(registry.get("akmai.adaptive.graph.lookup")
                .tag("band", "hot")
                .timer()
                .count()).isEqualTo(1);
    }

    @Test
    void rejectsUnboundedMetricTags() {
        AkmaiMetrics metrics = new AkmaiMetrics(new SimpleMeterRegistry());

        assertThatThrownBy(() ->
                metrics.adaptiveGraphLearning(
                        "citation",
                        "value with spaces",
                        1
                ))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
