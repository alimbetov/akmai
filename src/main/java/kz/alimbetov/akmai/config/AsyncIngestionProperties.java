package kz.alimbetov.akmai.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "akmai.ingestion.async-worker")
public record AsyncIngestionProperties(
        boolean enabled,
        int maxConcurrentIngestions,
        int workerQueueCapacity,
        int claimBatchSize,
        @NotNull Duration leaseDuration,
        @NotNull Duration heartbeatInterval,
        @NotNull Duration pollInterval,
        int maxAttempts,
        @NotNull Duration retryBaseDelay,
        @NotNull Duration retryMaxDelay,
        int payloadMaxInlineBytes
) {
    public AsyncIngestionProperties {
        if (maxConcurrentIngestions < 1 || maxConcurrentIngestions > 64) {
            throw new IllegalArgumentException(
                    "async ingestion max-concurrent-ingestions must be between 1 and 64"
            );
        }
        if (workerQueueCapacity < 1 || workerQueueCapacity > 4096) {
            throw new IllegalArgumentException(
                    "async ingestion worker-queue-capacity must be between 1 and 4096"
            );
        }
        if (claimBatchSize < 1 || claimBatchSize > 128) {
            throw new IllegalArgumentException(
                    "async ingestion claim-batch-size must be between 1 and 128"
            );
        }
        requirePositive("lease-duration", leaseDuration);
        requirePositive("heartbeat-interval", heartbeatInterval);
        requirePositive("poll-interval", pollInterval);
        if (heartbeatInterval.compareTo(leaseDuration.dividedBy(2)) >= 0) {
            throw new IllegalArgumentException(
                    "async ingestion heartbeat-interval must be less than half lease-duration"
            );
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException(
                    "async ingestion max-attempts must be between 1 and 100"
            );
        }
        requirePositive("retry-base-delay", retryBaseDelay);
        requirePositive("retry-max-delay", retryMaxDelay);
        if (retryBaseDelay.compareTo(retryMaxDelay) > 0) {
            throw new IllegalArgumentException(
                    "async ingestion retry-base-delay must not exceed retry-max-delay"
            );
        }
        if (payloadMaxInlineBytes < 1024 || payloadMaxInlineBytes > 50 * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "async ingestion payload-max-inline-bytes must be between 1KiB and 50MiB"
            );
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "async ingestion " + name + " must be positive"
            );
        }
    }
}
