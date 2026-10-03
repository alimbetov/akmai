package kz.alimbetov.akmai.knowledge.lifecycle;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RetentionScheduler.class);

    private final RetentionWorkerPool workerPool;
    private final RetentionProperties properties;
    private final DocumentGenerationRepository generationRepository;
    private final String workerId;
    private AkmaiMetrics metrics;

    public RetentionScheduler(
            RetentionWorkerPool workerPool,
            RetentionProperties properties,
            DocumentGenerationRepository generationRepository
    ) {
        this.workerPool = workerPool;
        this.properties = properties;
        this.generationRepository = generationRepository;
        this.workerId = ManagementFactory.getRuntimeMXBean().getName();
    }

    @Autowired(required = false)
    void setMetrics(AkmaiMetrics metrics) {
        this.metrics = metrics;
    }

    @Scheduled(
            cron = "${akmai.retention.cron}",
            zone = "${akmai.retention.zone}"
    )
    public void cleanupExpiredDocuments() {
        if (!properties.enabled()) {
            return;
        }

        Instant started = Instant.now();
        int initialSubmitted = 0;
        int runBudget = 0;
        int recovered = 0;
        String outcome = "SUCCESS";

        try {
            recovered = recoverAbandonedIngestions();

            runBudget = Math.multiplyExact(
                    properties.batchSize(),
                    properties.maxBatchesPerRun()
            );
            initialSubmitted = workerPool.drain(workerId, runBudget);
        } catch (RuntimeException exception) {
            outcome = "FAILED";
            LOGGER.error(
                    "retention_run event=failed initialSubmitted={} runBudget={} recovered={} errorType={}",
                    initialSubmitted,
                    runBudget,
                    recovered,
                    exception.getClass().getSimpleName()
            );
            throw exception;
        } finally {
            long backlog = workerPool.backlogCount();
            Duration duration = Duration.between(started, Instant.now());
            if (metrics != null) {
                metrics.retentionBacklog(backlog);
                metrics.retentionRun(outcome, duration);
            }
            LOGGER.info(
                    "retention_run event=completed outcome={} initialSubmitted={} runBudget={} recovered={} backlog={} durationMs={}",
                    outcome,
                    initialSubmitted,
                    runBudget,
                    recovered,
                    backlog,
                    duration.toMillis()
            );
        }
    }

    private int recoverAbandonedIngestions() {
        int total = 0;
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            int recovered = generationRepository.failStaleIngestionBatch(
                    properties.leaseDuration(),
                    properties.batchSize()
            );
            total += recovered;
            if (metrics != null) {
                metrics.staleIngestionsRecovered(recovered);
            }
            if (recovered > 0) {
                LOGGER.info(
                        "retention_recovery event=stale_ingestion_recovered count={}",
                        recovered
                );
            }
            if (recovered < properties.batchSize()) {
                break;
            }
        }
        return total;
    }
}
