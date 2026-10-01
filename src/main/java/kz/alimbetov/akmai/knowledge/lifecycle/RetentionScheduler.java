package kz.alimbetov.akmai.knowledge.lifecycle;

import java.lang.management.ManagementFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionScheduler {

    private final RetentionWorkerPool workerPool;
    private final RetentionProperties properties;
    private final DocumentLifecycleRepository lifecycleRepository;
    private final DocumentOperationLock documentOperationLock;
    private final String workerId;

    public RetentionScheduler(
            RetentionWorkerPool workerPool,
            RetentionProperties properties,
            DocumentLifecycleRepository lifecycleRepository,
            DocumentOperationLock documentOperationLock
    ) {
        this.workerPool = workerPool;
        this.properties = properties;
        this.lifecycleRepository = lifecycleRepository;
        this.documentOperationLock = documentOperationLock;
        this.workerId = ManagementFactory.getRuntimeMXBean().getName();
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
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant staleBefore = now.minus(properties.leaseDuration());
        for (String documentId : lifecycleRepository.findStaleIngestionDocumentIds(
                staleBefore,
                properties.batchSize()
        )) {
            documentOperationLock.tryAcquire(documentId).ifPresent(handle -> {
                try (handle) {
                    lifecycleRepository.failStaleIngestion(
                            documentId,
                            staleBefore,
                            now,
                            "abandoned ingestion exceeded recovery timeout"
                    );
                }
            });
        }
    }
}

