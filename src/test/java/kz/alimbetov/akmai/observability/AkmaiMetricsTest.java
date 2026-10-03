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
        metrics.archiveBacklog(4, 120, 600);
        metrics.archiveStore(
                "projection",
                100, 20, 300, 200, 3,
                1_000_000, 12, 250_000
        );
        metrics.archiveStore(
                "vector",
                100, 10, 300, 200, 2,
                2_000_000, 12, 500_000
        );
        metrics.archiveSample("SUCCESS", Duration.ofMillis(5));
        metrics.reconciliationRun(
                "SUCCESS", Duration.ofMillis(10), 2, 1
        );

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
        assertThat(registry.get("akmai.archive.pending.generations")
                .gauge().value()).isEqualTo(4.0);
        assertThat(registry.get("akmai.archive.pending.chunks")
                .gauge().value()).isEqualTo(120.0);
        assertThat(registry.get("akmai.archive.oldest.age.seconds")
                .gauge().value()).isEqualTo(600.0);
        assertThat(registry.get("akmai.archive.store.bytes")
                .tag("store", "projection")
                .gauge().value()).isEqualTo(1_000_000.0);
        assertThat(registry.get("akmai.archive.store.dead.rows.estimated")
                .tag("store", "vector")
                .gauge().value()).isEqualTo(10.0);
        assertThat(registry.get("akmai.archive.sample")
                .tag("outcome", "SUCCESS").timer().count()).isEqualTo(1);
        assertThat(registry.get("akmai.reconciliation.run")
                .tag("outcome", "SUCCESS").timer().count()).isEqualTo(1);
        assertThat(registry.get("akmai.reconciliation.cleaned.generations")
                .counter().count()).isEqualTo(2.0);
    }
}
