package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import kz.alimbetov.akmai.config.RetentionEconomicsProperties;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import org.junit.jupiter.api.Test;

class RetentionEconomicsSamplerTest {

    @Test
    void publishesLowCardinalityRetentionMetrics() {
        RetentionEconomicsService service =
                mock(RetentionEconomicsService.class);
        when(service.snapshot()).thenReturn(new RetentionEconomicsSnapshot(
                Instant.parse("2026-10-03T00:00:00Z"),
                3,
                90,
                420,
                2,
                8,
                64_000,
                new RetentionEconomicsSnapshot.StoreFootprint(
                        60, 10, 100, 40, 2,
                        1_000_000, 12, 200_000
                ),
                new RetentionEconomicsSnapshot.StoreFootprint(
                        60, 5, 100, 40, 1,
                        2_000_000, 12, 400_000
                )
        ));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AkmaiMetrics metrics = new AkmaiMetrics(registry);
        RetentionEconomicsSampler sampler = new RetentionEconomicsSampler(
                service,
                new RetentionEconomicsProperties(
                        true,
                        Duration.ofMinutes(15)
                ),
                metrics
        );

        sampler.sample();

        assertThat(registry.get(
                "akmai.retention.pending.verification.generations"
        ).gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("akmai.retention.tombstones.bytes")
                .gauge().value()).isEqualTo(64_000.0);
        assertThat(registry.get("akmai.retrieval.store.bytes")
                .tag("store", "projection")
                .gauge().value()).isEqualTo(1_000_000.0);
        assertThat(registry.get("akmai.retrieval.store.leaves")
                .tag("store", "vector")
                .gauge().value()).isEqualTo(12.0);
        assertThat(registry.get("akmai.retention.economics.sample")
                .tag("outcome", "SUCCESS")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void disabledSamplerDoesNotQueryDatabaseOrPublishMetrics() {
        RetentionEconomicsService service =
                mock(RetentionEconomicsService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AkmaiMetrics metrics = new AkmaiMetrics(registry);
        RetentionEconomicsSampler sampler = new RetentionEconomicsSampler(
                service,
                new RetentionEconomicsProperties(
                        false,
                        Duration.ofMinutes(15)
                ),
                metrics
        );

        sampler.sample();

        verify(service, never()).snapshot();
        assertThat(registry.find("akmai.retention.economics.sample")
                .timer()).isNull();
    }

    @Test
    void snapshotFailureIsIsolatedAndReportedAsFailedSample() {
        RetentionEconomicsService service =
                mock(RetentionEconomicsService.class);
        when(service.snapshot()).thenThrow(
                new IllegalStateException("statistics unavailable")
        );
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AkmaiMetrics metrics = new AkmaiMetrics(registry);
        RetentionEconomicsSampler sampler = new RetentionEconomicsSampler(
                service,
                new RetentionEconomicsProperties(
                        true,
                        Duration.ofMinutes(15)
                ),
                metrics
        );

        sampler.sample();

        assertThat(registry.get("akmai.retention.economics.sample")
                .tag("outcome", "FAILED")
                .timer().count()).isEqualTo(1);
    }
}
