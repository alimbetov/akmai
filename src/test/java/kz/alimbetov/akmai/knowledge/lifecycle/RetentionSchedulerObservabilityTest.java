package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import org.junit.jupiter.api.Test;

class RetentionSchedulerObservabilityTest {

    @Test
    void schedulerPublishesBacklogAndRunDurationMetrics() {
        RetentionWorkerPool workers = mock(RetentionWorkerPool.class);
        DocumentGenerationRepository generations =
                mock(DocumentGenerationRepository.class);
        RetentionProperties properties = new RetentionProperties(
                true,
                "0 0 * * * *",
                "UTC",
                10,
                2,
                3,
                1,
                1,
                Duration.ofMinutes(10),
                RetentionPolicy.PERMANENT,
                Duration.ofDays(90)
        );
        when(generations.failStaleIngestionBatch(
                Duration.ofMinutes(10),
                10
        )).thenReturn(0);
        when(workers.availableCapacity()).thenReturn(1);
        when(workers.claimAndSubmit(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(0);
        when(workers.backlogCount()).thenReturn(5L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AkmaiMetrics metrics = new AkmaiMetrics(registry);
        RetentionScheduler scheduler = new RetentionScheduler(
                workers,
                properties,
                generations
        );
        scheduler.setMetrics(metrics);

        scheduler.cleanupExpiredDocuments();

        assertThat(registry.get("akmai.retention.backlog").gauge().value())
                .isEqualTo(5.0);
        assertThat(registry.get("akmai.retention.run")
                .tag("outcome", "SUCCESS")
                .timer()
                .count()).isEqualTo(1);
    }
}
