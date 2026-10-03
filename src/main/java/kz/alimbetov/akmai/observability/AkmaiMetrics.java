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
    private final AtomicLong pendingVerificationGenerations = new AtomicLong();
    private final AtomicLong pendingVerificationChunks = new AtomicLong();
    private final AtomicLong oldestVerificationAgeSeconds = new AtomicLong();
    private final AtomicLong pendingTombstones = new AtomicLong();
    private final AtomicLong verifiedTombstones = new AtomicLong();
    private final AtomicLong tombstoneBytes = new AtomicLong();
    private final RetrievalStoreGauges projectionStore =
            new RetrievalStoreGauges();
    private final RetrievalStoreGauges vectorStore =
            new RetrievalStoreGauges();

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
                        "akmai.retention.pending.verification.generations",
                        pendingVerificationGenerations,
                        AtomicLong::get
                )
                .description("Retired generations awaiting consistency verification")
                .register(registry);
        Gauge.builder(
                        "akmai.retention.pending.verification.chunks",
                        pendingVerificationChunks,
                        AtomicLong::get
                )
                .description("Chunks represented by retired generations awaiting verification")
                .register(registry);
        Gauge.builder(
                        "akmai.retention.oldest.verification.age.seconds",
                        oldestVerificationAgeSeconds,
                        AtomicLong::get
                )
                .description("Age of the oldest retired generation awaiting verification")
                .register(registry);
        Gauge.builder(
                        "akmai.retention.tombstones.pending",
                        pendingTombstones,
                        AtomicLong::get
                )
                .description("Retirement tombstones awaiting verification")
                .register(registry);
        Gauge.builder(
                        "akmai.retention.tombstones.verified",
                        verifiedTombstones,
                        AtomicLong::get
                )
                .description("Verified retirement tombstones awaiting expiry")
                .register(registry);
        Gauge.builder(
                        "akmai.retention.tombstones.bytes",
                        tombstoneBytes,
                        AtomicLong::get
                )
                .description("Physical bytes used by retirement tombstones")
                .register(registry);

        registerRetrievalStore("projection", projectionStore);
        registerRetrievalStore("vector", vectorStore);
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

    public void retentionEconomicsBacklog(
            long pendingGenerations,
            long pendingChunks,
            long oldestAgeSeconds
    ) {
        pendingVerificationGenerations.set(Math.max(0L, pendingGenerations));
        pendingVerificationChunks.set(Math.max(0L, pendingChunks));
        oldestVerificationAgeSeconds.set(Math.max(0L, oldestAgeSeconds));
    }

    public void retentionTombstones(
            long pending,
            long verified,
            long bytes
    ) {
        pendingTombstones.set(Math.max(0L, pending));
        verifiedTombstones.set(Math.max(0L, verified));
        tombstoneBytes.set(Math.max(0L, bytes));
    }

    public void retrievalStore(
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
        RetrievalStoreGauges gauges = retrievalStoreGauges(store);
        gauges.estimatedLiveRows.set(Math.max(0L, estimatedLiveRows));
        gauges.estimatedDeadRows.set(Math.max(0L, estimatedDeadRows));
        gauges.insertedRows.set(Math.max(0L, insertedRows));
        gauges.deletedRows.set(Math.max(0L, deletedRows));
        gauges.autovacuumRuns.set(Math.max(0L, autovacuumRuns));
        gauges.totalBytes.set(Math.max(0L, totalBytes));
        gauges.leaves.set(Math.max(0L, leafCount));
        gauges.maxLeafBytes.set(Math.max(0L, maxLeafBytes));
    }

    public void retentionEconomicsSample(
            String outcome,
            Duration duration
    ) {
        Timer.builder("akmai.retention.economics.sample")
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

    private void registerRetrievalStore(
            String store,
            RetrievalStoreGauges gauges
    ) {
        registerRetrievalGauge(
                "akmai.retrieval.store.live.rows.estimated",
                "Estimated live rows in HOT retrieval leaves",
                store,
                gauges.estimatedLiveRows
        );
        registerRetrievalGauge(
                "akmai.retrieval.store.dead.rows.estimated",
                "Estimated dead rows in HOT retrieval leaves",
                store,
                gauges.estimatedDeadRows
        );
        registerRetrievalGauge(
                "akmai.retrieval.store.inserted.rows",
                "Rows inserted into HOT retrieval leaves since statistics reset",
                store,
                gauges.insertedRows
        );
        registerRetrievalGauge(
                "akmai.retrieval.store.deleted.rows",
                "Rows deleted from HOT retrieval leaves since statistics reset",
                store,
                gauges.deletedRows
        );
        registerRetrievalGauge(
                "akmai.retrieval.store.autovacuum.runs",
                "Autovacuum runs on HOT retrieval leaves since statistics reset",
                store,
                gauges.autovacuumRuns
        );
        registerRetrievalGauge(
                "akmai.retrieval.store.bytes",
                "Physical bytes used by HOT retrieval leaves and indexes",
                store,
                gauges.totalBytes
        );
        registerRetrievalGauge(
                "akmai.retrieval.store.leaves",
                "Number of HOT retrieval leaf partitions",
                store,
                gauges.leaves
        );
        registerRetrievalGauge(
                "akmai.retrieval.store.max.leaf.bytes",
                "Physical bytes used by the largest HOT retrieval leaf",
                store,
                gauges.maxLeafBytes
        );
    }

    private void registerRetrievalGauge(
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

    private RetrievalStoreGauges retrievalStoreGauges(String store) {
        return switch (store) {
            case "projection" -> projectionStore;
            case "vector" -> vectorStore;
            default -> throw new IllegalArgumentException(
                    "Unsupported retrieval store: " + store
            );
        };
    }

    private static final class RetrievalStoreGauges {
        private final AtomicLong estimatedLiveRows = new AtomicLong();
        private final AtomicLong estimatedDeadRows = new AtomicLong();
        private final AtomicLong insertedRows = new AtomicLong();
        private final AtomicLong deletedRows = new AtomicLong();
        private final AtomicLong autovacuumRuns = new AtomicLong();
        private final AtomicLong totalBytes = new AtomicLong();
        private final AtomicLong leaves = new AtomicLong();
        private final AtomicLong maxLeafBytes = new AtomicLong();
    }
}
