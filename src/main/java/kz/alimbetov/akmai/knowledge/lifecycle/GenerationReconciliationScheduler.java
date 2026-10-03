package kz.alimbetov.akmai.knowledge.lifecycle;

import java.time.Duration;
import java.time.Instant;
import kz.alimbetov.akmai.config.ReconciliationProperties;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GenerationReconciliationScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GenerationReconciliationScheduler.class);

    private final GenerationReconciliationService service;
    private final ReconciliationProperties properties;
    private AkmaiMetrics metrics;

    public GenerationReconciliationScheduler(
            GenerationReconciliationService service,
            ReconciliationProperties properties
    ) {
        this.service = service;
        this.properties = properties;
    }

    @Autowired(required = false)
    void setMetrics(AkmaiMetrics metrics) {
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${akmai.reconciliation.fixed-delay:PT5M}"
    )
    public void reconcile() {
        if (!properties.enabled()) {
            return;
        }

        Instant started = Instant.now();
        int cleanedTotal = 0;
        int batches = 0;
        String outcome = "SUCCESS";

        try {
            for (int batch = 0;
                    batch < properties.maxBatchesPerRun();
                    batch++) {
                int cleaned = service.reconcileBatch();
                batches++;
                cleanedTotal += cleaned;
                if (cleaned < properties.batchSize()) {
                    break;
                }
            }
        } catch (RuntimeException exception) {
            outcome = "FAILED";
            LOGGER.error(
                    "reconciliation_run event=failed cleanedGenerations={} batches={} errorType={}",
                    cleanedTotal,
                    batches,
                    exception.getClass().getSimpleName()
            );
            throw exception;
        } finally {
            Duration duration = Duration.between(started, Instant.now());
            if (metrics != null) {
                metrics.reconciliationRun(
                        outcome,
                        duration,
                        cleanedTotal,
                        batches
                );
            }
            LOGGER.info(
                    "reconciliation_run event=completed outcome={} cleanedGenerations={} batches={} durationMs={}",
                    outcome,
                    cleanedTotal,
                    batches,
                    duration.toMillis()
            );
        }
    }
}
