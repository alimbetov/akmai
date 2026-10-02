package kz.alimbetov.akmai.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AkmaiMetricsTest {

    @Test
    void exposesRetentionRunBacklogFailureAndLeaseSignals() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AkmaiMetrics metrics = new AkmaiMetrics(registry);

        metrics.retentionBacklog(7);
        metrics.retentionRun("SUCCESS", Duration.ofMillis(25));
        metrics.retentionClaimed(3);
        metrics.retentionResult("FAILED", 0);
        metrics.retentionStaleClaim();
        metrics.retentionLeaseLost();

        assertThat(registry.get("akmai.retention.backlog").gauge().value())
                .isEqualTo(7.0);
        assertThat(registry.get("akmai.retention.run")
                .tag("outcome", "SUCCESS").timer().count()).isEqualTo(1);
        assertThat(registry.get("akmai.retention.claims").counter().count())
                .isEqualTo(3.0);
        assertThat(registry.get("akmai.retention.cleanup")
                .tag("outcome", "FAILED").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("akmai.retention.stale.claim").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("akmai.retention.lease.lost").counter().count())
                .isEqualTo(1.0);
    }
}
