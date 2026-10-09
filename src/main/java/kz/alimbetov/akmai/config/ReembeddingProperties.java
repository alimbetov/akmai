package kz.alimbetov.akmai.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "akmai.reembedding")
public record ReembeddingProperties(
        boolean autoMigrate,
        @NotNull Duration drainTimeout,
        @NotNull Duration pollInterval,
        @DefaultValue("1m") @NotNull Duration leaseDuration,
        @DefaultValue("15s") @NotNull Duration heartbeatInterval
) {
    public ReembeddingProperties(
            boolean autoMigrate,
            Duration drainTimeout,
            Duration pollInterval,
            Duration leaseDuration
    ) {
        this(
                autoMigrate,
                drainTimeout,
                pollInterval,
                leaseDuration,
                defaultHeartbeat(leaseDuration)
        );
    }

    public ReembeddingProperties {
        if (drainTimeout == null
                || drainTimeout.isZero()
                || drainTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "reembedding drain-timeout must be positive"
            );
        }
        if (pollInterval == null
                || pollInterval.isZero()
                || pollInterval.isNegative()
                || pollInterval.compareTo(drainTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "reembedding poll-interval must be positive and below drain-timeout"
            );
        }
        if (leaseDuration == null
                || leaseDuration.isZero()
                || leaseDuration.isNegative()
                || leaseDuration.compareTo(pollInterval) <= 0) {
            throw new IllegalArgumentException(
                    "reembedding lease-duration must be positive and above poll-interval"
            );
        }
        if (heartbeatInterval == null
                || heartbeatInterval.isZero()
                || heartbeatInterval.isNegative()
                || heartbeatInterval.compareTo(leaseDuration.dividedBy(2)) >= 0) {
            throw new IllegalArgumentException(
                    "reembedding heartbeat-interval must be positive and below half the lease-duration"
            );
        }
    }

    private static Duration defaultHeartbeat(Duration leaseDuration) {
        if (leaseDuration == null
                || leaseDuration.isZero()
                || leaseDuration.isNegative()) {
            return Duration.ofSeconds(15);
        }
        Duration quarterLease = leaseDuration.dividedBy(4);
        if (quarterLease.isZero()) {
            return Duration.ofNanos(1);
        }
        return quarterLease.compareTo(Duration.ofSeconds(15)) < 0
                ? quarterLease
                : Duration.ofSeconds(15);
    }
}
