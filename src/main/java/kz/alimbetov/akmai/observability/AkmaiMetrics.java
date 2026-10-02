package kz.alimbetov.akmai.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class AkmaiMetrics {

    private final MeterRegistry registry;

    public AkmaiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void ingestion(
            String outcome,
            Duration duration,
            int chunks
    ) {
        Timer.builder("akmai.ingestion")
                .tag("outcome", outcome)
                .register(registry)
                .record(duration);
        registry.summary("akmai.ingestion.chunks").record(chunks);
    }

    public void retentionClaimed(int count) {
        registry.counter("akmai.retention.claims").increment(count);
    }

    public void retentionResult(String outcome, int deletedChunks) {
        registry.counter(
                "akmai.retention.cleanup",
                "outcome", outcome
        ).increment();
        if (deletedChunks > 0) {
            registry.summary("akmai.retention.deleted.chunks")
                    .record(deletedChunks);
        }
    }

    public void staleIngestionsRecovered(int count) {
        if (count > 0) {
            registry.counter("akmai.ingestion.stale.recovered")
                    .increment(count);
        }
    }

    public void idempotency(String outcome) {
        registry.counter(
                "akmai.ingestion.idempotency",
                "outcome", outcome
        ).increment();
    }
}
