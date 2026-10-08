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
    private final RetentionClaimRepository claimRepository;
    private final RetentionProperties properties;
    private final DocumentGenerationRepository generationRepository;
    private final String workerId;
    private AkmaiMetrics metrics;

    public RetentionScheduler(
            RetentionWorkerPool workerPool,
            RetentionClaimRepository claimRepository,
            RetentionProperties properties,
            DocumentGenerationRepository generationRepository
    ) {
        this.workerPool = workerPool;
        this.claimRepository = claimRepository;
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
        long backlog = -1L;
        long retryExhausted = -1L;
        String outcome = "SUCCESS";
        RuntimeException primaryFailure = null;

        try {
            recovered = recoverAbandonedIngestions();

            runBudget = Math.multiplyExact(
                    properties.batchSize(),
                    properties.maxBatchesPerRun()
            );
            initialSubmitted = workerPool.drain(workerId, runBudget);
        } catch (RuntimeException exception) {
            outcome = "FAILED";
            primaryFailure = exception;
            LOGGER.error(
                    "retention_run event=failed initialSubmitted={} runBudget={} recovered={} errorType={}",
                    initialSubmitted,
                    runBudget,
                    recovered,
                    exception.getClass().getSimpleName()
            );
        }

        try {
            backlog = workerPool.backlogCount();
            retryExhausted = claimRepository.countRetryExhausted(
                    properties.retryLimit()
            );
        } catch (RuntimeException observationFailure) {
            outcome = "FAILED";
            if (primaryFailure == null) {
                primaryFailure = observationFailure;
            } else {
                primaryFailure.addSuppressed(observationFailure);
            }
            LOGGER.warn(
                    "retention_run event=post_run_observation_failed errorType={}",
                    observationFailure.getClass().getSimpleName()
            );
        }

        Duration duration = Duration.between(started, Instant.now());
        if (metrics != null) {
            if (backlog >= 0) {
                metrics.retentionBacklog(backlog);
            }
            metrics.retentionRun(outcome, duration);
        }
        LOGGER.info(
                "retention_run event=completed outcome={} initialSubmitted={} runBudget={} recovered={} backlog={} retryExhausted={} durationMs={}",
                outcome,
                initialSubmitted,
                runBudget,
                recovered,
                backlog,
                retryExhausted,
                duration.toMillis()
        );

        if (primaryFailure != null) {
            throw primaryFailure;
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
