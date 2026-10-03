package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import kz.alimbetov.akmai.config.ReconciliationProperties;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import org.junit.jupiter.api.Test;

class GenerationReconciliationSchedulerObservabilityTest {

    @Test
    void publishesRunThroughputAndDuration() {
        GenerationReconciliationService service =
                mock(GenerationReconciliationService.class);
        when(service.reconcileBatch()).thenReturn(3);

        ReconciliationProperties properties = new ReconciliationProperties(
                true,
                10,
                2,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5)
        );
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AkmaiMetrics metrics = new AkmaiMetrics(registry);

        GenerationReconciliationScheduler scheduler =
                new GenerationReconciliationScheduler(service, properties);
        scheduler.setMetrics(metrics);
        scheduler.reconcile();

        assertThat(registry.get("akmai.reconciliation.run")
                .tag("outcome", "SUCCESS")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("akmai.reconciliation.cleaned.generations")
                .counter().count()).isEqualTo(3.0);
        assertThat(registry.get("akmai.reconciliation.batches")
                .summary().totalAmount()).isEqualTo(1.0);
    }
}
