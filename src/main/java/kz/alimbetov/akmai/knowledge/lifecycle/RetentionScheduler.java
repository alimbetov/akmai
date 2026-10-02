package kz.alimbetov.akmai.knowledge.lifecycle;

import java.lang.management.ManagementFactory;
import kz.alimbetov.akmai.observability.AkmaiMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionScheduler {

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

        recoverAbandonedIngestions();

        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            if (workerPool.availableCapacity() == 0) {
                return;
            }
            if (workerPool.claimAndSubmit(workerId) == 0) {
                return;
            }
        }
    }

    private void recoverAbandonedIngestions() {
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            int recovered = generationRepository.failStaleIngestionBatch(
                    properties.leaseDuration(),
                    properties.batchSize()
            );
            if (metrics != null) {
                metrics.staleIngestionsRecovered(recovered);
            }
            if (recovered < properties.batchSize()) {
                return;
            }
        }
    }
}
