package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import kz.alimbetov.akmai.config.ArchiveEconomicsProperties;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import org.junit.jupiter.api.Test;

class ArchiveEconomicsSamplerTest {

    @Test
    void publishesLowCardinalityArchiveMetrics() {
        ArchiveEconomicsService service = mock(ArchiveEconomicsService.class);
        when(service.snapshot()).thenReturn(new ArchiveEconomicsSnapshot(
                Instant.parse("2026-10-03T00:00:00Z"),
                3,
                90,
                420,
                new ArchiveEconomicsSnapshot.StoreFootprint(
                        60, 10, 100, 40, 2,
                        1_000_000, 12, 200_000
                ),
                new ArchiveEconomicsSnapshot.StoreFootprint(
                        60, 5, 100, 40, 1,
                        2_000_000, 12, 400_000
                )
        ));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AkmaiMetrics metrics = new AkmaiMetrics(registry);
        ArchiveEconomicsSampler sampler = new ArchiveEconomicsSampler(
                service,
                new ArchiveEconomicsProperties(
                        true,
                        Duration.ofMinutes(15)
                ),
                metrics
        );

        sampler.sample();

        assertThat(registry.get("akmai.archive.pending.generations")
                .gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("akmai.archive.store.bytes")
                .tag("store", "projection")
                .gauge().value()).isEqualTo(1_000_000.0);
        assertThat(registry.get("akmai.archive.store.leaves")
                .tag("store", "vector")
                .gauge().value()).isEqualTo(12.0);
        assertThat(registry.get("akmai.archive.sample")
                .tag("outcome", "SUCCESS")
                .timer().count()).isEqualTo(1);
    }
}
