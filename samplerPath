package kz.alimbetov.akmai.knowledge.lifecycle;

import java.time.Duration;
import java.time.Instant;
import kz.alimbetov.akmai.config.RetentionEconomicsProperties;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionEconomicsSampler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RetentionEconomicsSampler.class);

    private final RetentionEconomicsService service;
    private final RetentionEconomicsProperties properties;
    private final AkmaiMetrics metrics;

    public RetentionEconomicsSampler(
            RetentionEconomicsService service,
            RetentionEconomicsProperties properties,
            AkmaiMetrics metrics
    ) {
        this.service = service;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString =
                    "${akmai.retention-economics.fixed-delay:PT15M}"
    )
    public void sample() {
        if (!properties.enabled()) {
            return;
        }

        Instant started = Instant.now();
        try {
            RetentionEconomicsSnapshot snapshot = service.snapshot();
            metrics.retentionEconomicsBacklog(
                    snapshot.pendingGenerations(),
                    snapshot.pendingChunks(),
                    snapshot.oldestRetiredAgeSeconds()
            );
            metrics.retentionTombstones(
                    snapshot.pendingTombstones(),
                    snapshot.verifiedTombstones(),
                    snapshot.tombstoneBytes()
            );
            publishStore("projection", snapshot.projection());
            publishStore("vector", snapshot.vector());

            Duration duration = Duration.between(started, Instant.now());
            metrics.retentionEconomicsSample("SUCCESS", duration);
            LOGGER.info(
                    "retention_economics event=sample pendingGenerations={} pendingChunks={} oldestAgeSeconds={} pendingTombstones={} verifiedTombstones={} tombstoneBytes={} projectionLiveEstimated={} projectionDeadEstimated={} projectionBytes={} vectorLiveEstimated={} vectorDeadEstimated={} vectorBytes={} durationMs={}",
                    snapshot.pendingGenerations(),
                    snapshot.pendingChunks(),
                    snapshot.oldestRetiredAgeSeconds(),
                    snapshot.pendingTombstones(),
                    snapshot.verifiedTombstones(),
                    snapshot.tombstoneBytes(),
                    snapshot.projection().estimatedLiveRows(),
                    snapshot.projection().estimatedDeadRows(),
                    snapshot.projection().totalBytes(),
                    snapshot.vector().estimatedLiveRows(),
                    snapshot.vector().estimatedDeadRows(),
                    snapshot.vector().totalBytes(),
                    duration.toMillis()
            );
        } catch (RuntimeException exception) {
            Duration duration = Duration.between(started, Instant.now());
            metrics.retentionEconomicsSample("FAILED", duration);
            LOGGER.warn(
                    "retention_economics event=failed errorType={} durationMs={}",
                    exception.getClass().getSimpleName(),
                    duration.toMillis()
            );
        }
    }

    private void publishStore(
            String store,
            RetentionEconomicsSnapshot.StoreFootprint footprint
    ) {
        metrics.retrievalStore(
                store,
                footprint.estimatedLiveRows(),
                footprint.estimatedDeadRows(),
                footprint.insertedRows(),
                footprint.deletedRows(),
                footprint.autovacuumRuns(),
                footprint.totalBytes(),
                footprint.leafCount(),
                footprint.maxLeafBytes()
        );
    }
}
