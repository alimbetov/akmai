package kz.alimbetov.akmai.knowledge.graph;

import java.time.Duration;
import java.time.Instant;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import kz.alimbetov.akmai.runtimeconfig.AppParameterKey;
import kz.alimbetov.akmai.runtimeconfig.AppParameterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AdaptiveGraphMaintenanceScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AdaptiveGraphMaintenanceScheduler.class);

    private final AdaptiveGraphMaintenanceService service;
    private final AdaptiveGraphProperties properties;
    private final AkmaiMetrics metrics;
    private final AppParameterService appParameterService;

    public AdaptiveGraphMaintenanceScheduler(
            AdaptiveGraphMaintenanceService service,
            AdaptiveGraphProperties properties,
            AkmaiMetrics metrics
    ) {
        this(service, properties, metrics, null);
    }

    @Autowired
    public AdaptiveGraphMaintenanceScheduler(
            AdaptiveGraphMaintenanceService service,
            AdaptiveGraphProperties properties,
            AkmaiMetrics metrics,
            AppParameterService appParameterService
    ) {
        this.service = service;
        this.properties = properties;
        this.metrics = metrics;
        this.appParameterService = appParameterService;
    }

    private boolean runtimeEnabled(
            AppParameterKey key,
            boolean fallback
    ) {
        return appParameterService == null
                ? fallback
                : appParameterService.isEnabled(key);
    }

    @Scheduled(
            fixedDelayString =
                    "${akmai.adaptive-graph.maintenance.fixed-delay:PT5M}"
    )
    public void maintain() {
        if (!runtimeEnabled(
                AppParameterKey.ADAPTIVE_GRAPH_MAINTENANCE_ENABLED,
                properties.maintenanceEnabled()
        )) {
            return;
        }

        Instant started = Instant.now();
        int scored = 0;
        int evicted = 0;
        int purged = 0;
        int batches = 0;
        String outcome = "SUCCESS";

        try {
            for (int batch = 0;
                    batch < properties.maintenance().maxBatchesPerRun();
                    batch++) {
                AdaptiveGraphMaintenanceService.MaintenanceBatch result =
                        service.maintainBatch();
                batches++;
                scored += result.scored();
                evicted += result.evicted();
                purged += result.purged();

                result.transitions().forEach((transition, count) ->
                        metrics.adaptiveGraphBandTransition(
                                transition.from().name(),
                                transition.to().name(),
                                count
                        )
                );

                if (!result.saturated()) {
                    break;
                }
            }
        } catch (RuntimeException exception) {
            outcome = "FAILED";
            LOGGER.error(
                    "adaptive_graph_maintenance event=failed "
                            + "scored={} evicted={} purged={} batches={} "
                            + "errorType={}",
                    scored,
                    evicted,
                    purged,
                    batches,
                    exception.getClass().getSimpleName()
            );
            throw exception;
        } finally {
            Duration duration = Duration.between(started, Instant.now());
            metrics.adaptiveGraphMaintenance(
                    outcome,
                    duration,
                    scored,
                    evicted + purged
            );
            LOGGER.info(
                    "adaptive_graph_maintenance event=completed "
                            + "outcome={} scored={} evicted={} purged={} "
                            + "batches={} durationMs={}",
                    outcome,
                    scored,
                    evicted,
                    purged,
                    batches,
                    duration.toMillis()
            );
        }
    }
}
