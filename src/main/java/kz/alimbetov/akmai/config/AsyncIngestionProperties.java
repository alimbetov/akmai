package kz.alimbetov.akmai.config;

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
        Duration leaseDuration,
        Duration heartbeatInterval,
        Duration pollInterval,
        int maxAttempts,
        Duration retryBaseDelay,
        Duration retryMaxDelay,
        int payloadMaxInlineBytes
) {
    private static final int DEFAULT_MAX_CONCURRENT = 3;
    private static final int DEFAULT_WORKER_QUEUE_CAPACITY = 3;
    private static final int DEFAULT_CLAIM_BATCH_SIZE = 3;
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(2);
    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final Duration DEFAULT_RETRY_BASE_DELAY = Duration.ofSeconds(5);
    private static final Duration DEFAULT_RETRY_MAX_DELAY = Duration.ofMinutes(5);
    private static final int DEFAULT_PAYLOAD_MAX_INLINE_BYTES = 1024 * 1024;

    public AsyncIngestionProperties {
        maxConcurrentIngestions = defaultIfZero(
                maxConcurrentIngestions,
                DEFAULT_MAX_CONCURRENT
        );
        workerQueueCapacity = defaultIfZero(
                workerQueueCapacity,
                DEFAULT_WORKER_QUEUE_CAPACITY
        );
        claimBatchSize = defaultIfZero(
                claimBatchSize,
                DEFAULT_CLAIM_BATCH_SIZE
        );
        leaseDuration = defaultIfNull(
                leaseDuration,
                DEFAULT_LEASE_DURATION
        );
        heartbeatInterval = defaultIfNull(
                heartbeatInterval,
                DEFAULT_HEARTBEAT_INTERVAL
        );
        pollInterval = defaultIfNull(
                pollInterval,
                DEFAULT_POLL_INTERVAL
        );
        maxAttempts = defaultIfZero(maxAttempts, DEFAULT_MAX_ATTEMPTS);
        retryBaseDelay = defaultIfNull(
                retryBaseDelay,
                DEFAULT_RETRY_BASE_DELAY
        );
        retryMaxDelay = defaultIfNull(
                retryMaxDelay,
                DEFAULT_RETRY_MAX_DELAY
        );
        payloadMaxInlineBytes = defaultIfZero(
                payloadMaxInlineBytes,
                DEFAULT_PAYLOAD_MAX_INLINE_BYTES
        );

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

    private static int defaultIfZero(int value, int defaultValue) {
        return value == 0 ? defaultValue : value;
    }

    private static <T> T defaultIfNull(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "async ingestion " + name + " must be positive"
            );
        }
    }
}
