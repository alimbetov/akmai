package kz.alimbetov.akmai.knowledge.lifecycle;

import java.time.Duration;
import java.time.Instant;
import kz.alimbetov.akmai.config.ArchiveEconomicsProperties;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ArchiveEconomicsSampler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ArchiveEconomicsSampler.class);

    private final ArchiveEconomicsService service;
    private final ArchiveEconomicsProperties properties;
    private final AkmaiMetrics metrics;

    public ArchiveEconomicsSampler(
            ArchiveEconomicsService service,
            ArchiveEconomicsProperties properties,
            AkmaiMetrics metrics
    ) {
        this.service = service;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${akmai.archive-economics.fixed-delay:PT15M}"
    )
    public void sample() {
        if (!properties.enabled()) {
            return;
        }

        Instant started = Instant.now();
        try {
            ArchiveEconomicsSnapshot snapshot = service.snapshot();
            metrics.archiveBacklog(
                    snapshot.pendingGenerations(),
                    snapshot.pendingChunks(),
                    snapshot.oldestRetiredAgeSeconds()
            );
            publishStore("projection", snapshot.projection());
            publishStore("vector", snapshot.vector());

            Duration duration = Duration.between(started, Instant.now());
            metrics.archiveSample("SUCCESS", duration);
            LOGGER.info(
                    "archive_economics event=sample pendingGenerations={} pendingChunks={} oldestAgeSeconds={} projectionLiveEstimated={} projectionDeadEstimated={} projectionBytes={} vectorLiveEstimated={} vectorDeadEstimated={} vectorBytes={} durationMs={}",
                    snapshot.pendingGenerations(),
                    snapshot.pendingChunks(),
                    snapshot.oldestRetiredAgeSeconds(),
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
            metrics.archiveSample("FAILED", duration);
            LOGGER.warn(
                    "archive_economics event=failed errorType={} durationMs={}",
                    exception.getClass().getSimpleName(),
                    duration.toMillis()
            );
        }
    }

    private void publishStore(
            String store,
            ArchiveEconomicsSnapshot.StoreFootprint footprint
    ) {
        metrics.archiveStore(
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
