package kz.alimbetov.akmai.knowledge.lifecycle;

import java.lang.management.ManagementFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionScheduler {

    private final RetentionWorkerPool workerPool;
    private final RetentionProperties properties;
    private final DocumentLifecycleRepository lifecycleRepository;
    private final String workerId;

    public RetentionScheduler(
            RetentionWorkerPool workerPool,
            RetentionProperties properties,
            DocumentLifecycleRepository lifecycleRepository
    ) {
        this.workerPool = workerPool;
        this.properties = properties;
        this.lifecycleRepository = lifecycleRepository;
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

        java.time.Instant now = java.time.Instant.now();
        lifecycleRepository.failStaleIngestions(
                now.minus(properties.leaseDuration()),
                now,
                "abandoned ingestion exceeded recovery timeout"
        );

        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            if (workerPool.availableCapacity() == 0) {
                return;
            }
            if (workerPool.claimAndSubmit(workerId) == 0) {
                return;
            }
        }
    }
}
