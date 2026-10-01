package kz.alimbetov.akmai.knowledge.lifecycle;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "akmai.retention")
public record RetentionProperties(
        boolean enabled,
        String cron,
        String zone,
        int batchSize,
        int maxBatchesPerRun,
        int retryLimit,
        RetentionPolicy defaultPolicy,
        Duration defaultTtl
) {

    public RetentionProperties {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("retention batch-size must be > 0");
        }
        if (maxBatchesPerRun <= 0) {
            throw new IllegalArgumentException("retention max-batches-per-run must be > 0");
        }
        if (retryLimit <= 0) {
            throw new IllegalArgumentException("retention retry-limit must be > 0");
        }
        if (defaultPolicy == RetentionPolicy.TTL
                && (defaultTtl == null || defaultTtl.isZero() || defaultTtl.isNegative())) {
            throw new IllegalArgumentException(
                    "retention default-ttl must be positive for TTL default policy"
            );
        }
    }
}
