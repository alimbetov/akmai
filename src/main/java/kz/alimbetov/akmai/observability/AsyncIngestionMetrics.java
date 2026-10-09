package kz.alimbetov.akmai.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class AsyncIngestionMetrics {

    private final MeterRegistry registry;
    private final AtomicLong queueDepth = new AtomicLong();
    private final AtomicLong processing = new AtomicLong();

    public AsyncIngestionMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(
                        "akmai.async.ingestion.queue.depth",
                        queueDepth,
                        AtomicLong::get
                )
                .description("Durable async ingestion jobs awaiting completion")
                .register(registry);
        Gauge.builder(
                        "akmai.async.ingestion.processing",
                        processing,
                        AtomicLong::get
                )
                .description("Async document ingestions executing on this instance")
                .register(registry);
    }

    public void queueDepth(long value) {
        queueDepth.set(Math.max(0L, value));
    }

    public void processingStarted() {
        processing.incrementAndGet();
    }

    public void processingFinished() {
        processing.updateAndGet(value -> Math.max(0L, value - 1));
    }

    public void queueWait(Duration duration) {
        Timer.builder("akmai.async.ingestion.queue.wait")
                .description("Time from durable acceptance to worker claim")
                .publishPercentileHistogram()
                .register(registry)
                .record(nonNegative(duration));
    }

    public void processing(String outcome, Duration duration) {
        Timer.builder("akmai.async.ingestion.processing.duration")
                .description("Async document worker processing duration")
                .tag("outcome", boundedTag(outcome))
                .publishPercentileHistogram()
                .register(registry)
                .record(nonNegative(duration));
    }

    public void retry() {
        registry.counter("akmai.async.ingestion.retry").increment();
    }

    public void failed() {
        registry.counter("akmai.async.ingestion.failed").increment();
    }

    public void ingested() {
        registry.counter("akmai.async.ingestion.ingested").increment();
    }

    public void leaseLost() {
        registry.counter("akmai.async.ingestion.job.lease.lost").increment();
    }

    public void replayRecovery() {
        registry.counter("akmai.async.ingestion.replay.recovery").increment();
    }

    public void ambiguous() {
        registry.counter("akmai.async.ingestion.ambiguous").increment();
    }

    private Duration nonNegative(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return Duration.ZERO;
        }
        return duration;
    }

    private String boundedTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.length() <= 32
                ? normalized
                : normalized.substring(0, 32);
    }
}
