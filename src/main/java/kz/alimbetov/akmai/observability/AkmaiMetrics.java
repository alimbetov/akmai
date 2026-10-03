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
    private final AtomicLong archivePendingGenerations = new AtomicLong();
    private final AtomicLong archivePendingChunks = new AtomicLong();
    private final AtomicLong archiveOldestAgeSeconds = new AtomicLong();
    private final ArchiveStoreGauges projectionArchive = new ArchiveStoreGauges();
    private final ArchiveStoreGauges vectorArchive = new ArchiveStoreGauges();

    public AkmaiMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(
                        "akmai.retention.backlog",
                        retentionBacklog,
                        AtomicLong::get
                )
                .description("Retention rows currently eligible for cleanup")
                .register(registry);

        Gauge.builder(
                        "akmai.archive.pending.generations",
                        archivePendingGenerations,
                        AtomicLong::get
                )
                .description("Retired generations waiting for physical purge")
                .register(registry);
        Gauge.builder(
                        "akmai.archive.pending.chunks",
                        archivePendingChunks,
                        AtomicLong::get
                )
                .description("Chunks in retired generations waiting for purge")
                .register(registry);
        Gauge.builder(
                        "akmai.archive.oldest.age.seconds",
                        archiveOldestAgeSeconds,
                        AtomicLong::get
                )
                .description("Age of the oldest retired generation awaiting purge")
                .register(registry);

        registerArchiveStore("projection", projectionArchive);
        registerArchiveStore("vector", vectorArchive);
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

    public void archiveBacklog(
            long pendingGenerations,
            long pendingChunks,
            long oldestAgeSeconds
    ) {
        archivePendingGenerations.set(Math.max(0L, pendingGenerations));
        archivePendingChunks.set(Math.max(0L, pendingChunks));
        archiveOldestAgeSeconds.set(Math.max(0L, oldestAgeSeconds));
    }

    public void archiveStore(
            String store,
            long estimatedLiveRows,
            long estimatedDeadRows,
            long insertedRows,
            long deletedRows,
            long autovacuumRuns,
            long totalBytes,
            long leafCount,
            long maxLeafBytes
    ) {
        ArchiveStoreGauges gauges = archiveStoreGauges(store);
        gauges.estimatedLiveRows.set(Math.max(0L, estimatedLiveRows));
        gauges.estimatedDeadRows.set(Math.max(0L, estimatedDeadRows));
        gauges.insertedRows.set(Math.max(0L, insertedRows));
        gauges.deletedRows.set(Math.max(0L, deletedRows));
        gauges.autovacuumRuns.set(Math.max(0L, autovacuumRuns));
        gauges.totalBytes.set(Math.max(0L, totalBytes));
        gauges.leafCount.set(Math.max(0L, leafCount));
        gauges.maxLeafBytes.set(Math.max(0L, maxLeafBytes));
    }

    public void archiveSample(String outcome, Duration duration) {
        Timer.builder("akmai.archive.sample")
                .tag("outcome", outcome)
                .register(registry)
                .record(duration);
    }

    public void reconciliationRun(
            String outcome,
            Duration duration,
            int cleanedGenerations,
            int batches
    ) {
        Timer.builder("akmai.reconciliation.run")
                .tag("outcome", outcome)
                .register(registry)
                .record(duration);
        if (cleanedGenerations > 0) {
            registry.counter("akmai.reconciliation.cleaned.generations")
                    .increment(cleanedGenerations);
        }
        registry.summary("akmai.reconciliation.batches").record(batches);
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

    private void registerArchiveStore(
            String store,
            ArchiveStoreGauges gauges
    ) {
        registerArchiveGauge(
                "akmai.archive.store.live.rows.estimated",
                "Estimated live rows in archive leaves",
                store,
                gauges.estimatedLiveRows
        );
        registerArchiveGauge(
                "akmai.archive.store.dead.rows.estimated",
                "Estimated dead rows in archive leaves",
                store,
                gauges.estimatedDeadRows
        );
        registerArchiveGauge(
                "akmai.archive.store.inserted.rows",
                "Rows inserted into archive leaves since statistics reset",
                store,
                gauges.insertedRows
        );
        registerArchiveGauge(
                "akmai.archive.store.deleted.rows",
                "Rows deleted from archive leaves since statistics reset",
                store,
                gauges.deletedRows
        );
        registerArchiveGauge(
                "akmai.archive.store.autovacuum.runs",
                "Autovacuum runs on archive leaves since statistics reset",
                store,
                gauges.autovacuumRuns
        );
        registerArchiveGauge(
                "akmai.archive.store.bytes",
                "Physical bytes used by archive leaves and their indexes",
                store,
                gauges.totalBytes
        );
        registerArchiveGauge(
                "akmai.archive.store.leaves",
                "Number of archive leaf partitions",
                store,
                gauges.leafCount
        );
        registerArchiveGauge(
                "akmai.archive.store.max.leaf.bytes",
                "Physical bytes used by the largest archive leaf",
                store,
                gauges.maxLeafBytes
        );
    }

    private void registerArchiveGauge(
            String name,
            String description,
            String store,
            AtomicLong value
    ) {
        Gauge.builder(name, value, AtomicLong::get)
                .tag("store", store)
                .description(description)
                .register(registry);
    }

    private ArchiveStoreGauges archiveStoreGauges(String store) {
        return switch (store) {
            case "projection" -> projectionArchive;
            case "vector" -> vectorArchive;
            default -> throw new IllegalArgumentException(
                    "Unsupported archive store: " + store
            );
        };
    }

    private static final class ArchiveStoreGauges {
        private final AtomicLong estimatedLiveRows = new AtomicLong();
        private final AtomicLong estimatedDeadRows = new AtomicLong();
        private final AtomicLong insertedRows = new AtomicLong();
        private final AtomicLong deletedRows = new AtomicLong();
        private final AtomicLong autovacuumRuns = new AtomicLong();
        private final AtomicLong totalBytes = new AtomicLong();
        private final AtomicLong leafCount = new AtomicLong();
        private final AtomicLong maxLeafBytes = new AtomicLong();
    }
}
