package kz.alimbetov.akmai.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class AkmaiMetrics {

    private final MeterRegistry registry;
    private final AtomicLong retentionBacklog = new AtomicLong();

    public AkmaiMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(
                        "akmai.retention.backlog",
                        retentionBacklog,
                        AtomicLong::get
                )
                .description("Retention rows currently eligible for cleanup")
                .register(registry);
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

    public void retentionRun(String outcome, Duration duration) {
        Timer.builder("akmai.retention.run")
                .tag("outcome", outcome)
                .register(registry)
                .record(duration);
    }

    public void retentionBacklog(long count) {
        retentionBacklog.set(Math.max(0L, count));
    }

    public void retentionClaimed(int count) {
        if (count > 0) {
            registry.counter("akmai.retention.claims").increment(count);
        }
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

    public void retentionStaleClaim() {
        registry.counter("akmai.retention.stale.claim").increment();
    }

    public void retentionLeaseLost() {
        registry.counter("akmai.retention.lease.lost").increment();
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
